package com.sad.programmer.redis.lock;

import com.sad.programmer.redis.common.RedisUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;

/**
 * 分布式锁 Redis 实现。
 *
 * <h3>实现原理</h3>
 * <ol>
 *   <li>使用 SET key value NX EX 实现互斥 + 过期</li>
 *   <li>value 为 UUID + threadId 组合，防止误释放</li>
 *   <li>释放时用 Lua 脚本保证原子性：先比较 value 再删除</li>
 * </ol>
 *
 * <h3>防死锁</h3>
 * <p>锁带过期时间，即使持有者崩溃也能自动释放。</p>
 *
 * @author sad-programmer
 */
public class RedisLockImpl implements RedisLock {

    /** 获取锁的重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 50;

    /** Redis 中存储锁的键名 */
    private final String lockKey;

    /** 锁的过期时间（秒），防止持有者崩溃导致死锁 */
    private final int lockExpireSeconds;

    /** 锁的唯一标识值，格式为 UUID:threadId，用于安全释放锁 */
    private final String lockValue;

    /** 当前持有锁的线程引用，用于判断锁归属 */
    private volatile Thread ownerThread;

    /**
     * 创建分布式锁。
     *
     * @param lockKey           锁键名，不允许为空
     * @param lockExpireSeconds 锁过期时间（秒），必须大于 0
     * @throws IllegalArgumentException 当 lockKey 为空或 lockExpireSeconds <= 0 时
     */
    public RedisLockImpl(String lockKey, int lockExpireSeconds) {
        if (lockKey == null || lockKey.isEmpty()) {
            throw new IllegalArgumentException("lockKey 不允许为空");
        }
        if (lockExpireSeconds <= 0) {
            throw new IllegalArgumentException("lockExpireSeconds 必须 > 0");
        }
        this.lockKey = lockKey;
        this.lockExpireSeconds = lockExpireSeconds;
        this.lockValue = UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
    }

    /**
     * 尝试获取锁，支持超时等待。
     *
     * <p>如果当前线程已持有锁，直接返回 true（可重入语义简化版）。</p>
     *
     * @param timeoutMs 超时时间（毫秒），0 表示不等待立即返回
     * @return true 表示获取成功，false 表示超时未获取
     * @throws InterruptedException 等待过程中被中断
     */
    @Override
    public boolean tryLock(long timeoutMs) throws InterruptedException {
        if (isHeldByCurrentThread()) {
            return true;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (tryAcquire()) {
                ownerThread = Thread.currentThread();
                return true;
            }
            if (timeoutMs == 0) {
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(RETRY_INTERVAL_MS);
        }
    }

    /**
     * 释放锁。
     *
     * <p>使用 Lua 脚本原子性地校验锁归属并删除，防止误释放其他线程的锁。</p>
     *
     * @throws IllegalStateException 当前线程不是锁的持有者时
     */
    @Override
    public void unlock() {
        if (!isHeldByCurrentThread()) {
            throw new IllegalStateException("当前线程不是锁的持有者");
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            // Lua 脚本：先比较 value，再删除（原子操作）
            String lua = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                         "return redis.call('del', KEYS[1]) " +
                         "else return 0 end";
            jedis.eval(lua, 1, lockKey, lockValue);
        } finally {
            ownerThread = null;
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 判断当前线程是否持有锁。
     *
     * @return true 表示当前线程持有锁
     */
    @Override
    public boolean isHeldByCurrentThread() {
        return ownerThread == Thread.currentThread();
    }

    /**
     * 尝试在 Redis 中获取锁（单次尝试）。
     *
     * <p>使用 SET NX EX 命令实现互斥加锁。</p>
     *
     * @return true 表示获取成功
     */
    private boolean tryAcquire() {
        Jedis jedis = RedisUtil.getResource();
        try {
            SetParams params = new SetParams().nx().ex(lockExpireSeconds);
            String result = jedis.set(lockKey, lockValue, params);
            return "OK".equals(result);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }
}
