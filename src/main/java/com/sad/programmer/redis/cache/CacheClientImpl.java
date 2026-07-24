package com.sad.programmer.redis.cache;

import com.sad.programmer.redis.common.RedisUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

/**
 * CacheClient 的 Redis 实现。
 *
 * <h3>防穿透策略</h3>
 * <p>当查询数据库不存在时，缓存空值 {@link #NULL_VALUE}，短 TTL 防止反复穿透。</p>
 *
 * <h3>防击穿策略</h3>
 * <p>热点 key 过期时，使用 SETNX 互斥锁保证只有一个线程回源，其余线程重试。</p>
 *
 * @author sad-programmer
 */
public class CacheClientImpl implements CacheClient {

    /** 空值占位符，用于缓存穿透防护，标识数据库中不存在的键 */
    public static final String NULL_VALUE = "NULL";

    /** 空值缓存的过期时间（秒），防止长期占用内存 */
    private static final int NULL_TTL_SECONDS = 60;

    /** 互斥锁重试间隔（毫秒），防击穿时等待锁释放的轮询间隔 */
    private static final long LOCK_RETRY_INTERVAL_MS = 50;

    /** 互斥锁最大重试次数，超过后放弃等待 */
    private static final int LOCK_MAX_RETRIES = 20;

    /**
     * 获取缓存值。
     *
     * <p>如果缓存值为 {@link #NULL_VALUE}，返回 null（防穿透）。</p>
     *
     * @param key 缓存键，不允许 null
     * @return 缓存值，不存在或为空值占位时返回 null
     * @throws IllegalArgumentException 当 key 为 null 时
     */
    @Override
    public String get(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            String value = jedis.get(key);
            if (NULL_VALUE.equals(value)) {
                return null;
            }
            return value;
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 设置缓存值。
     *
     * @param key        缓存键，不允许 null
     * @param value      缓存值，为 null 时存储 {@link #NULL_VALUE}
     * @param ttlSeconds 过期时间（秒），必须大于 0
     * @throws IllegalArgumentException 当 key 为 null 或 ttlSeconds <= 0 时
     */
    @Override
    public void put(String key, String value, int ttlSeconds) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds 必须 > 0，当前值: " + ttlSeconds);
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.setex(key, ttlSeconds, value != null ? value : NULL_VALUE);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 缓存空值（防穿透专用）。
     *
     * <p>使用短 {@link #NULL_TTL_SECONDS} TTL，防止长期占用内存。</p>
     *
     * @param key 缓存键，不允许 null
     * @throws IllegalArgumentException 当 key 为 null 时
     */
    public void putNull(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.setex(key, NULL_TTL_SECONDS, NULL_VALUE);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 获取互斥锁（防击穿专用）。
     *
     * <p>使用 SETNX + 过期时间实现，防止死锁。</p>
     *
     * @param lockKey    锁键名，不允许 null
     * @param ttlSeconds 锁过期时间（秒），必须大于 0
     * @return true 表示获取成功，false 表示锁已被其他线程持有
     */
    public boolean tryLock(String lockKey, int ttlSeconds) {
        Jedis jedis = RedisUtil.getResource();
        try {
            SetParams params = new SetParams().nx().ex(ttlSeconds);
            String result = jedis.set(lockKey, "1", params);
            return "OK".equals(result);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 释放互斥锁。
     *
     * @param lockKey 锁键名，不允许 null
     */
    public void unlock(String lockKey) {
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.del(lockKey);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 带防穿透 + 防击穿的查询方法。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查缓存，命中直接返回</li>
     *   <li>未命中，尝试获取互斥锁</li>
     *   <li>获取到锁，双重检查后查询 DB 并回写缓存</li>
     *   <li>未获取到锁，休眠后重试（最多 {@link #LOCK_MAX_RETRIES} 次）</li>
     * </ol>
     *
     * @param key        缓存键，不允许 null
     * @param ttlSeconds 缓存 TTL（秒）
     * @param dbQuery    数据库查询回调函数
     * @return 缓存值或 DB 查询结果，超时返回 null
     * @throws IllegalArgumentException 当 key 为 null 时
     */
    public String getOrLoad(String key, int ttlSeconds, DatabaseQuery dbQuery) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }

        // 1. 查缓存
        String cached = get(key);
        if (cached != null) {
            return cached;
        }

        // 2. 尝试互斥锁
        String lockKey = "lock:" + key;
        boolean locked = tryLock(lockKey, 10);
        if (locked) {
            try {
                // 双重检查
                cached = get(key);
                if (cached != null) {
                    return cached;
                }
                // 3. 查 DB
                String dbValue = dbQuery.query(key);
                if (dbValue == null) {
                    putNull(key);
                    return null;
                }
                put(key, dbValue, ttlSeconds);
                return dbValue;
            } finally {
                unlock(lockKey);
            }
        }

        // 4. 未获取到锁，重试
        for (int i = 0; i < LOCK_MAX_RETRIES; i++) {
            try {
                Thread.sleep(LOCK_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            cached = get(key);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    /**
     * 删除缓存。
     *
     * @param key 缓存键，不允许 null
     * @return true 表示删除成功，false 表示键不存在
     * @throws IllegalArgumentException 当 key 为 null 时
     */
    @Override
    public boolean delete(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            return jedis.del(key) > 0;
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 判断键是否存在。
     *
     * @param key 缓存键，不允许 null
     * @return true 表示存在，false 表示不存在
     * @throws IllegalArgumentException 当 key 为 null 时
     */
    @Override
    public boolean exists(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key 不允许为 null");
        }
        Jedis jedis = RedisUtil.getResource();
        try {
            return jedis.exists(key);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 数据库查询回调接口。
     *
     * <p>用于 {@link #getOrLoad(String, int, DatabaseQuery)} 中延迟执行数据库查询。</p>
     */
    public interface DatabaseQuery {

        /**
         * 查询数据库。
         *
         * @param key 查询键
         * @return 查询结果，不存在返回 null
         */
        String query(String key);
    }
}
