package com.sad.programmer.redis.cache;

/**
 * 缓存客户端接口（SDD 规格）。
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>get: O(1) 复杂度</li>
 *   <li>线程安全</li>
 *   <li>空值缓存防穿透（可配置 TTL）</li>
 * </ul>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>get(key) — 存在返回值，不存在返回 null</li>
 *   <li>put(key, value, ttlSeconds) — 设置键值对，带过期时间</li>
 *   <li>delete(key) — 删除键</li>
 *   <li>exists(key) — 判断键是否存在</li>
 * </ul>
 *
 * @author sad-programmer
 */
public interface CacheClient {

    /**
     * 获取缓存值。
     *
     * @param key 缓存键，不允许 null
     * @return 缓存值，不存在返回 null
     */
    String get(String key);

    /**
     * 设置缓存值。
     *
     * @param key         缓存键，不允许 null
     * @param value       缓存值，不允许 null
     * @param ttlSeconds  过期时间（秒），必须 > 0
     */
    void put(String key, String value, int ttlSeconds);

    /**
     * 删除缓存。
     *
     * @param key 缓存键，不允许 null
     * @return 是否删除成功
     */
    boolean delete(String key);

    /**
     * 判断键是否存在。
     *
     * @param key 缓存键，不允许 null
     * @return 是否存在
     */
    boolean exists(String key);
}
