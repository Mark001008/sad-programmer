


package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;


/**
 * FutureTask 源码阅读版。
 *
 * <p>核心模型：FutureTask 同时是 Runnable 和 Future。它可以被线程池执行，也可以被调用方 get 等待结果。
 * 内部用 state 表示任务生命周期，用 outcome 保存结果或异常，用 WaitNode 链表保存等待 get 的线程。</p>
 */
public class FutureTask<V> implements RunnableFuture<V> {


    /**
     * 任务状态。
     *
     * <p>状态只会单向流转：NEW -> COMPLETING -> NORMAL/EXCEPTIONAL，或 NEW -> CANCELLED/INTERRUPTING/INTERRUPTED。</p>
     */
    private volatile int state;

    /**
     * 初始状态，任务尚未执行完成。
     */
    private static final int NEW          = 0;

    /**
     * 任务正在写入结果的过渡状态。
     */
    private static final int COMPLETING   = 1;

    /**
     * 正常完成。
     */
    private static final int NORMAL       = 2;

    /**
     * 执行异常。
     */
    private static final int EXCEPTIONAL  = 3;

    /**
     * 已取消，未中断执行线程。
     */
    private static final int CANCELLED    = 4;

    /**
     * 正在中断执行线程。
     */
    private static final int INTERRUPTING = 5;

    /**
     * 已完成中断处理。
     */
    private static final int INTERRUPTED  = 6;


    /**
     * 实际业务任务。
     */
    private Callable<V> callable;

    /**
     * 执行结果或异常。
     *
     * <p>正常完成时保存返回值；异常完成时保存 Throwable。</p>
     */
    private Object outcome;

    /**
     * 当前执行任务的线程。
     */
    private volatile Thread runner;

    /**
     * 等待 get 结果的线程链表。
     */
    private volatile WaitNode waiters;


    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        // 根据最终状态解释 outcome：正常返回值、取消异常或执行异常。
        Object x = outcome;
        if (s == NORMAL)
            return (V)x;
        if (s >= CANCELLED)
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }


    public FutureTask(Callable<V> callable) {
        // FutureTask 构造后只是 NEW 状态，真正执行发生在 run()。
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;
    }


    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        // cancel 只能从 NEW 状态发起。状态已经完成后，取消会失败。
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        // mayInterruptIfRunning=true 时，尝试中断正在执行 callable 的线程。
                        t.interrupt();
                } finally {
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }


    public V get() throws InterruptedException, ExecutionException {
        // get 会阻塞等待任务进入最终状态，然后通过 report 返回结果或抛异常。
        int s = state;
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }


    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        // 限时 get，超时还没完成时抛 TimeoutException，不会自动取消任务。
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }


    protected void done() { }


    protected void set(V v) {
        // 正常完成：先 CAS 进入 COMPLETING，再写 outcome，最后把状态设置成 NORMAL 并唤醒等待线程。
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
            finishCompletion();
        }
    }


    protected void setException(Throwable t) {
        // 异常完成：outcome 保存异常对象，get 时会包装成 ExecutionException 抛出。
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
            finishCompletion();
        }
    }

    public void run() {
        // run 由执行线程调用，通常是线程池工作线程。
        // CAS 设置 runner，保证同一个 FutureTask 不会被多个线程重复执行。
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    // callable 抛异常时，任务进入 EXCEPTIONAL 状态。
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran)
                    set(result);
            }
        } finally {


            runner = null;


            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }


    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call();
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {


            runner = null;


            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }


    private void handlePossibleCancellationInterrupt(int s) {


        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield();


    }


    static final class WaitNode {
        /**
         * 等待结果的线程。
         */
        volatile Thread thread;

        /**
         * 下一个等待节点。
         */
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }


    private void finishCompletion() {
        // 任务完成后唤醒所有 get 阻塞线程，并调用 done 钩子。

        for (WaitNode q; (q = waiters) != null;) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null;
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;
    }


    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // get 的等待主循环：
        // 任务未完成时，把当前线程加入 waiters 链表，然后 park。
        // 任务完成、超时或中断时退出等待。
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            }
            else if (s == COMPLETING)
                Thread.yield();
            else if (q == null)
                q = new WaitNode();
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
                LockSupport.park(this);
        }
    }


    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null)
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }


    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
