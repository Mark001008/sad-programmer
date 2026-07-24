package com.sad.programmer.redis.lock;

/**
 * 分布式锁接口（SDD 规格）。
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>基于 Redis SETNX + 过期时间实现</li>
 *   <li>支持锁续期（看门狗机制）</li>
 *   <li>支持可重入（同一线程可多次加锁）</li>
 *   <li>释放锁时校验持有者，防止误释放</li>
 * </ul>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>tryLock(timeout, unit) — 尝试获取锁，支持超时等待</li>
 *   <li>unlock() — 释放锁，校验持有者</li>
 *   <li>isHeldByCurrentThread() — 判断当前线程是否持有锁</li>
 * </ul>
 *
 * @author sad-programmer
 */
public interface RedisLock {

    /**
     * 尝试获取锁。
     *
     * @param timeoutMs 超时时间（毫秒），0 表示不等待
     * @return true=获取成功，false=超时未获取
     * @throws InterruptedException 等待过程中被中断
     */
    boolean tryLock(long timeoutMs) throws InterruptedException;

    /**
     * 释放锁。
     *
     * <p>只有持有者才能释放，否则抛出异常。</p>
     *
     * @throws IllegalStateException 当前线程不是锁的持有者
     */
    void unlock();

    /**
     * 判断当前线程是否持有锁。
     *
     * @return true=持有
     */
    boolean isHeldByCurrentThread();
}
