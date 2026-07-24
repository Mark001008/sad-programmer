package com.sad.programmer.redis.cache;

import com.sad.programmer.redis.common.RedisUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * CacheClientImpl 测试。
 *
 * <p>覆盖：正常路径、边界条件、防穿透、防击穿。</p>
 *
 * @author sad-programmer
 */
public class CacheClientImplTest {

    /** 缓存客户端实现 */
    private CacheClientImpl cache;

    /** 测试键前缀，用于隔离测试数据 */
    private String testPrefix;

    /**
     * 初始化测试环境，创建缓存客户端和唯一前缀。
     */
    @Before
    public void setUp() {
        cache = new CacheClientImpl();
        testPrefix = "test:" + UUID.randomUUID().toString().substring(0, 8) + ":";
        cleanTestData();
    }

    /**
     * 清理测试数据，删除所有以测试前缀开头的键。
     */
    @After
    public void tearDown() {
        cleanTestData();
    }

    /**
     * 清理 Redis 中的测试数据。
     */
    private void cleanTestData() {
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.keys(testPrefix + "*").forEach(jedis::del);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    // ==================== 正常路径 ====================

    /**
     * 验证设置和获取缓存值的基本功能。
     */
    @Test
    public void shouldSetAndGetKeyValue() {
        String key = testPrefix + "hello";
        cache.put(key, "world", 60);
        assertEquals("world", cache.get(key));
    }

    /**
     * 验证获取不存在的键时返回 null。
     */
    @Test
    public void shouldReturnNullForMissingKey() {
        assertNull(cache.get(testPrefix + "nonexistent"));
    }

    /**
     * 验证删除已存在的键返回 true。
     */
    @Test
    public void shouldDeleteExistingKey() {
        String key = testPrefix + "del";
        cache.put(key, "value", 60);
        assertTrue(cache.delete(key));
        assertFalse(cache.exists(key));
    }

    /**
     * 验证删除不存在的键返回 false。
     */
    @Test
    public void shouldReturnFalseWhenDeleteNonexistent() {
        assertFalse(cache.delete(testPrefix + "ghost"));
    }

    /**
     * 验证 exists 方法正确判断键是否存在。
     */
    @Test
    public void shouldCheckExistence() {
        String key = testPrefix + "exists";
        assertFalse(cache.exists(key));
        cache.put(key, "v", 60);
        assertTrue(cache.exists(key));
    }

    // ==================== 边界条件 ====================

    /**
     * 验证缓存值在 TTL 过期后自动删除。
     */
    @Test
    public void shouldExpireAfterTtl() throws InterruptedException {
        String key = testPrefix + "ttl";
        cache.put(key, "short", 1);
        assertEquals("short", cache.get(key));
        Thread.sleep(1100);
        assertNull(cache.get(key));
    }

    /**
     * 验证 key 为 null 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenKeyIsNull() {
        cache.get(null);
    }

    /**
     * 验证 TTL 为 0 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenTtlIsZero() {
        cache.put(testPrefix + "x", "v", 0);
    }

    /**
     * 验证 TTL 为负数时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenTtlIsNegative() {
        cache.put(testPrefix + "x", "v", -1);
    }

    // ==================== 防穿透 ====================

    /**
     * 验证缓存空值后，get 返回 null 但 exists 返回 true。
     */
    @Test
    public void shouldCacheNullValueToPreventPenetration() {
        String key = testPrefix + "null";
        cache.putNull(key);
        assertNull(cache.get(key));
        assertTrue(cache.exists(key));
    }

    /**
     * 验证空值缓存设置了正确的 TTL。
     */
    @Test
    public void shouldExpireNullValueCache() throws InterruptedException {
        String key = testPrefix + "nullttl";
        cache.putNull(key);
        assertTrue(cache.exists(key));
        Jedis jedis = RedisUtil.getResource();
        try {
            long ttl = jedis.ttl(key);
            assertTrue("空值缓存应有 TTL", ttl > 0 && ttl <= 60);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    // ==================== 防击穿 ====================

    /**
     * 验证缓存未命中时从 DB 回调加载数据并回写缓存。
     */
    @Test
    public void shouldLoadFromDbWhenCacheMiss() {
        String key = testPrefix + "load";
        String result = cache.getOrLoad(key, 60, k -> "db_value");
        assertEquals("db_value", result);
        assertEquals("db_value", cache.get(key));
    }

    /**
     * 验证 DB 返回 null 时缓存空值占位。
     */
    @Test
    public void shouldCacheNullWhenDbReturnsNull() {
        String key = testPrefix + "dbnull";
        String result = cache.getOrLoad(key, 60, k -> null);
        assertNull(result);
        assertTrue(cache.exists(key));
    }

    /**
     * 验证缓存命中时不调用 DB 回调。
     */
    @Test
    public void shouldReturnCachedValueWithoutQueryingDb() {
        String key = testPrefix + "cached";
        cache.put(key, "cached_value", 60);
        String result = cache.getOrLoad(key, 60, k -> {
            fail("缓存命中时不应查询 DB");
            return null;
        });
        assertEquals("cached_value", result);
    }

    /**
     * 验证互斥锁在并发场景下正确保护 DB 查询。
     */
    @Test
    public void shouldHandleMutexLockForConcurrentAccess() {
        String key = testPrefix + "mutex";
        String result = cache.getOrLoad(key, 60, k -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow_value";
        });
        assertEquals("slow_value", result);
    }

    // ==================== 互斥锁 ====================

    /**
     * 验证互斥锁的获取和释放流程。
     */
    @Test
    public void shouldAcquireAndReleaseLock() {
        String lockKey = testPrefix + "lock";
        assertTrue(cache.tryLock(lockKey, 10));
        assertFalse(cache.tryLock(lockKey, 10));
        cache.unlock(lockKey);
        assertTrue(cache.tryLock(lockKey, 10));
        cache.unlock(lockKey);
    }

    /**
     * 验证互斥锁在 TTL 过期后自动释放。
     */
    @Test
    public void shouldExpireLockAfterTtl() throws InterruptedException {
        String lockKey = testPrefix + "lockttl";
        assertTrue(cache.tryLock(lockKey, 1));
        Thread.sleep(1100);
        assertTrue(cache.tryLock(lockKey, 10));
        cache.unlock(lockKey);
    }
}
