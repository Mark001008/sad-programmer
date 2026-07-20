package com.sad.programmer.concurrent.lock;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 单 JVM 内的任务防重入保护器。
 *
 * <p>适用于本地定时任务、缓存刷新、文件扫描等“上一轮没结束就跳过下一轮”的场景。
 * 多实例部署时该类不能防止其他 JVM 同时执行任务，需要使用数据库任务表、Redis 分布式锁或调度中心分片。</p>
 */
public class LocalTaskSingleFlightGuard {

    /**
     * 任务执行锁。
     *
     * <p>使用 {@code tryLock()} 而不是 {@code lock()}，是为了抢不到锁时快速返回，避免调度线程阻塞。</p>
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 尝试执行任务。
     *
     * @param task 待执行任务
     * @return true 表示本次抢到锁并执行完成；false 表示已有任务正在执行，本次跳过
     */
    public boolean tryRun(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        // 抢不到锁说明上一轮任务还在执行，直接跳过，不阻塞当前调度线程。
        if (!lock.tryLock()) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            // ReentrantLock 是显式锁，必须在 finally 中释放，避免任务异常导致锁永久占用。
            lock.unlock();
        }
    }
}
