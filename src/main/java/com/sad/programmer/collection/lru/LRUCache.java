package com.sad.programmer.collection.lru;

/**
 * 最近最少使用缓存接口（SDD 规格定义）。
 *
 * <p>面试高频考点：基于 HashMap + 双向链表实现 O(1) 的 get/put。
 * 当缓存达到容量上限时，自动淘汰最久未访问的条目。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface LRUCache<K, V> {

    /**
     * 获取缓存值。
     *
     * <p>命中后该条目变为最近使用。</p>
     *
     * @param key 缓存键
     * @return 缓存值，不存在时返回 null
     * @throws IllegalArgumentException key 为 null
     */
    V get(K key);

    /**
     * 写入缓存。
     *
     * <p>键已存在时更新值并移到最近使用位置。
     * 容量满时淘汰最久未访问的条目。</p>
     *
     * @param key 缓存键
     * @param value 缓存值
     * @throws IllegalArgumentException key 或 value 为 null
     */
    void put(K key, V value);

    /**
     * 删除缓存条目。
     *
     * @param key 缓存键
     * @return 被删除的值，不存在时返回 null
     * @throws IllegalArgumentException key 为 null
     */
    V remove(K key);

    /**
     * 判断缓存是否包含指定键。
     *
     * @param key 缓存键
     * @return true 表示存在
     * @throws IllegalArgumentException key 为 null
     */
    boolean containsKey(K key);

    /**
     * 返回当前缓存条目数。
     *
     * @return 条目数
     */
    int size();

    /**
     * 返回缓存容量。
     *
     * @return 容量上限
     */
    int capacity();

    /**
     * 清空缓存。
     */
    void clear();
}
