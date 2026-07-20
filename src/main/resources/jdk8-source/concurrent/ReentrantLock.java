


package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;


public class ReentrantLock implements Lock, java.io.Serializable {
    /*
     * 中文阅读提示：
     * ReentrantLock 是 AQS 独占模式的典型实现。
     * 主线：state 表示重入次数，exclusiveOwnerThread 表示持锁线程，公平/非公平体现在抢锁前是否检查队列前驱。
     * 面试重点：和 synchronized 的区别、可重入原理、公平非公平、tryLock/lockInterruptibly、Condition。
     */

    private static final long serialVersionUID = 7373984872572414699L;

    // 所有加锁/解锁能力都委托给 Sync。具体实现可能是公平锁，也可能是非公平锁。
    private final Sync sync;


    abstract static class Sync extends AbstractQueuedSynchronizer {
        /*
         * ReentrantLock 的同步器基类，继承 AQS。
         * state 表示重入次数；exclusiveOwnerThread 表示当前持锁线程。
         * 公平锁和非公平锁的差异主要体现在 lock/tryAcquire 的抢锁策略。
         */

        private static final long serialVersionUID = -5179523762034025860L;


        abstract void lock();


        final boolean nonfairTryAcquire(int acquires) {
            // 非公平抢锁：
            // 如果 state 为 0，当前线程直接 CAS 抢锁，不检查前面是否已有排队线程。
            // 如果当前线程已经持锁，state 增加，实现可重入。
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

        protected final boolean tryRelease(int releases) {
            // 释放锁：
            // state 减 releases。只有 state 减到 0，才算真正释放锁，并清空持锁线程。
            // 如果不是持锁线程调用 unlock，会抛 IllegalMonitorStateException。
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }

        protected final boolean isHeldExclusively() {


            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }


        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() {
            return getState() != 0;
        }


        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0);
        }
    }


    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;


        final void lock() {
            // 非公平锁先尝试直接 CAS 抢锁，失败后才进入 AQS 队列。
            // 这就是“插队”来源：新来的线程可能抢在已排队线程前面拿到锁。
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }


    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final void lock() {
            acquire(1);
        }


        protected final boolean tryAcquire(int acquires) {
            // 公平锁抢锁前会检查 hasQueuedPredecessors：
            // 如果同步队列中已有前驱等待线程，当前线程不能插队。
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (!hasQueuedPredecessors() &&
                    compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }
    }


    public ReentrantLock() {
        // 默认非公平锁。吞吐量通常更高，但允许新线程插队。
        sync = new NonfairSync();
    }


    public ReentrantLock(boolean fair) {
        // fair=true 创建公平锁；fair=false 创建非公平锁。
        sync = fair ? new FairSync() : new NonfairSync();
    }


    public void lock() {
        // 普通加锁，不响应中断。获取不到锁会进入 AQS 队列等待。
        sync.lock();
    }


    public void lockInterruptibly() throws InterruptedException {
        // 可中断加锁。等待锁期间如果被 interrupt，会抛 InterruptedException。
        sync.acquireInterruptibly(1);
    }


    public boolean tryLock() {
        // 立即尝试抢锁，不等待。
        // 注意：即使是公平锁，tryLock() 这里也走非公平尝试，可能插队。
        return sync.nonfairTryAcquire(1);
    }


    public boolean tryLock(long timeout, TimeUnit unit)
            throws InterruptedException {
        // 限时等待锁。等待期间可响应中断，超时返回 false。
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }


    public void unlock() {
        // 解锁。必须由持锁线程调用；重入几次就要 unlock 几次。
        sync.release(1);
    }


    public Condition newCondition() {
        // 创建条件队列。一个 ReentrantLock 可以创建多个 Condition，适合更精细的等待/唤醒分组。
        return sync.newCondition();
    }


    public int getHoldCount() {
        return sync.getHoldCount();
    }


    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }


    public boolean isLocked() {
        return sync.isLocked();
    }


    public final boolean isFair() {
        return sync instanceof FairSync;
    }


    protected Thread getOwner() {
        return sync.getOwner();
    }


    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }


    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }


    public final int getQueueLength() {
        return sync.getQueueLength();
    }


    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }


    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }


    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }


    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }


    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                                   "[Unlocked]" :
                                   "[Locked by thread " + o.getName() + "]");
    }
}
