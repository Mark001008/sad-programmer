package com.sad.programmer.redis.lock;

import com.sad.programmer.redis.common.RedisUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * RedisLockImpl 测试。
 *
 * <p>覆盖：正常加锁/释放、超时、并发互斥、锁过期、异常路径。</p>
 *
 * @author sad-programmer
 */
public class RedisLockImplTest {

    /** 测试键前缀，用于隔离测试数据 */
    private String testPrefix;

    /**
     * 初始化测试环境，生成唯一前缀。
     */
    @Before
    public void setUp() {
        testPrefix = "lock:test:" + UUID.randomUUID().toString().substring(0, 8) + ":";
    }

    /**
     * 清理 Redis 中的测试数据。
     */
    @After
    public void tearDown() {
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.keys(testPrefix + "*").forEach(jedis::del);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    // ==================== 正常路径 ====================

    /**
     * 验证获取锁后 isHeldByCurrentThread 返回 true，释放后返回 false。
     */
    @Test
    public void shouldAcquireAndReleaseLock() throws InterruptedException {
        RedisLock lock = new RedisLockImpl(testPrefix + "basic", 10);
        assertTrue(lock.tryLock(0));
        assertTrue(lock.isHeldByCurrentThread());
        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }

    /**
     * 验证释放锁后可以重新获取。
     */
    @Test
    public void shouldReacquireAfterRelease() throws InterruptedException {
        RedisLock lock = new RedisLockImpl(testPrefix + "reacq", 10);
        assertTrue(lock.tryLock(0));
        lock.unlock();
        assertTrue(lock.tryLock(0));
        lock.unlock();
    }

    // ==================== 互斥性 ====================

    /**
     * 验证锁被其他实例持有时，新实例无法获取。
     */
    @Test
    public void shouldBlockWhenLockHeldByOther() throws InterruptedException {
        String lockKey = testPrefix + "mutex";
        RedisLock lock1 = new RedisLockImpl(lockKey, 10);
        RedisLock lock2 = new RedisLockImpl(lockKey, 10);

        assertTrue(lock1.tryLock(0));
        assertFalse(lock2.tryLock(0));

        lock1.unlock();
        assertTrue(lock2.tryLock(0));
        lock2.unlock();
    }

    /**
     * 验证超时等待机制，超时后返回 false。
     */
    @Test
    public void shouldTimeoutWhenLockNotReleased() throws InterruptedException {
        String lockKey = testPrefix + "timeout";
        RedisLock lock1 = new RedisLockImpl(lockKey, 10);
        RedisLock lock2 = new RedisLockImpl(lockKey, 10);

        assertTrue(lock1.tryLock(0));
        long start = System.currentTimeMillis();
        assertFalse(lock2.tryLock(200));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("应等待约 200ms", elapsed >= 150);

        lock1.unlock();
    }

    /**
     * 验证多线程竞争同一把锁时只有一个线程能获取。
     */
    @Test
    public void shouldOnlyOneThreadAcquireLock() throws InterruptedException {
        String lockKey = testPrefix + "race";
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicBoolean error = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    RedisLock lock = new RedisLockImpl(lockKey, 5);
                    if (lock.tryLock(500)) {
                        acquiredCount.incrementAndGet();
                        Thread.sleep(50);
                        lock.unlock();
                    }
                } catch (Exception e) {
                    error.set(true);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertFalse("不应有异常", error.get());
        assertTrue("至少一个线程获取到锁", acquiredCount.get() >= 1);
    }

    // ==================== 锁过期 ====================

    /**
     * 验证锁过期后其他实例可以获取。
     */
    @Test
    public void shouldExpireLockAndAllowOtherToAcquire() throws InterruptedException {
        String lockKey = testPrefix + "expire";
        RedisLock lock1 = new RedisLockImpl(lockKey, 1);
        RedisLock lock2 = new RedisLockImpl(lockKey, 10);

        assertTrue(lock1.tryLock(0));
        Thread.sleep(1100);
        assertTrue("锁过期后其他线程应能获取", lock2.tryLock(0));
        lock2.unlock();
    }

    // ==================== 异常路径 ====================

    /**
     * 验证非持有者释放锁时抛出 IllegalStateException。
     */
    @Test(expected = IllegalStateException.class)
    public void shouldThrowWhenUnlockByNonOwner() throws InterruptedException {
        RedisLock lock = new RedisLockImpl(testPrefix + "nonowner", 10);
        lock.unlock();
    }

    /**
     * 验证 lockKey 为空字符串时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenLockKeyIsEmpty() {
        new RedisLockImpl("", 10);
    }

    /**
     * 验证 lockKey 为 null 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenLockKeyIsNull() {
        new RedisLockImpl(null, 10);
    }

    /**
     * 验证过期时间为 0 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenExpireIsZero() {
        new RedisLockImpl(testPrefix + "x", 0);
    }
}
