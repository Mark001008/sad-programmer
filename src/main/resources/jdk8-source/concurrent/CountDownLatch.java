


package java.util.concurrent;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;


/**
 * CountDownLatch 源码阅读版。
 *
 * <p>核心模型：AQS 的 state 表示剩余计数。await 使用共享模式等待 state 归零；
 * countDown 使用共享模式递减 state，减到 0 时唤醒所有等待线程。</p>
 */
public class CountDownLatch {

    /**
     * CountDownLatch 的 AQS 实现。
     *
     * <p>这里使用共享模式，因为计数归零后不是只允许一个线程通过，而是所有 await 的线程都可以继续执行。</p>
     */
    private static final class Sync extends AbstractQueuedSynchronizer {

        /**
         * 序列化版本号。
         */
        private static final long serialVersionUID = 4982264981922014374L;

        /**
         * 初始化同步器。
         *
         * @param count 初始计数，写入 AQS state
         */
        Sync(int count) {
            setState(count);
        }

        /**
         * 返回当前剩余计数。
         *
         * @return AQS state 当前值
         */
        int getCount() {
            return getState();
        }

        /**
         * 共享模式获取。
         *
         * <p>state 为 0 时返回正数，表示 await 可以通过；state 不为 0 时返回负数，表示需要进入 AQS 队列等待。</p>
         *
         * @param acquires AQS 共享获取参数，此处没有业务含义
         * @return 正数表示成功，负数表示失败
         */
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        /**
         * 共享模式释放。
         *
         * <p>每次 countDown 都会把 state 减 1。只有从 1 减到 0 的线程返回 true，
         * 触发 AQS 的共享唤醒传播，放行所有 await 线程。</p>
         *
         * @param releases AQS 共享释放参数，此处没有业务含义
         * @return true 表示计数已经归零，需要唤醒等待线程
         */
        protected boolean tryReleaseShared(int releases) {

            for (;;) {
                int c = getState();
                if (c == 0)
                    return false;
                int nextc = c-1;
                // CAS 递减 state，避免多个线程同时 countDown 导致计数丢失。
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
    }

    /**
     * 内部同步器。
     */
    private final Sync sync;


    /**
     * 创建 CountDownLatch。
     *
     * @param count 初始计数
     */
    public CountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }


    /**
     * 等待计数归零。
     *
     * @throws InterruptedException 等待期间被中断
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }


    /**
     * 限时等待计数归零。
     *
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return true 表示计数在超时前归零；false 表示等待超时
     * @throws InterruptedException 等待期间被中断
     */
    public boolean await(long timeout, TimeUnit unit)
        throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }


    /**
     * 计数减一。
     *
     * <p>计数已经为 0 后继续调用不会产生效果，也不会把计数减成负数。</p>
     */
    public void countDown() {
        sync.releaseShared(1);
    }


    /**
     * 返回当前剩余计数。
     *
     * @return 当前计数
     */
    public long getCount() {
        return sync.getCount();
    }


    /**
     * 返回字符串描述。
     *
     * @return 包含当前计数的字符串
     */
    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }
}
