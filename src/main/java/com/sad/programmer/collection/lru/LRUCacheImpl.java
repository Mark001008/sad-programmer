package com.sad.programmer.collection.lru;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于 HashMap + 双向链表实现的 LRU 缓存。
 *
 * <p>面试核心考点：</p>
 * <ul>
 *   <li>HashMap 保证 O(1) 查找</li>
 *   <li>双向链表维护访问顺序，保证 O(1) 插入/删除</li>
 *   <li>get/put 操作同时维护两个数据结构的一致性</li>
 * </ul>
 *
 * <p>时间复杂度：get O(1)、put O(1)、remove O(1)。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class LRUCacheImpl<K, V> implements LRUCache<K, V> {

    /**
     * 缓存容量上限。
     */
    private final int capacity;

    /**
     * 键到链表节点的映射，保证 O(1) 查找。
     */
    private final Map<K, Node<K, V>> map;

    /**
     * 虚拟头节点，next 指向最近使用的节点。
     */
    private final Node<K, V> head;

    /**
     * 虚拟尾节点，prev 指向最久未使用的节点。
     */
    private final Node<K, V> tail;

    /**
     * 创建指定容量的 LRU 缓存。
     *
     * @param capacity 缓存容量，必须大于 0
     * @throws IllegalArgumentException capacity 小于等于 0
     */
    public LRUCacheImpl(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.map = new HashMap<K, Node<K, V>>(capacity);
        // 虚拟头尾节点，避免边界判断
        this.head = new Node<K, V>(null, null);
        this.tail = new Node<K, V>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public V get(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }
        // 命中后移到链表头部（最近使用）
        moveToHead(node);
        return node.value;
    }

    @Override
    public void put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        Node<K, V> existing = map.get(key);
        if (existing != null) {
            // 更新已有节点的值并移到头部
            existing.value = value;
            moveToHead(existing);
            return;
        }
        // 容量满时淘汰尾部节点（最久未使用）
        if (map.size() >= capacity) {
            evictTail();
        }
        // 插入新节点到头部
        Node<K, V> newNode = new Node<K, V>(key, value);
        addToHead(newNode);
        map.put(key, newNode);
    }

    @Override
    public V remove(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        Node<K, V> node = map.remove(key);
        if (node == null) {
            return null;
        }
        removeNode(node);
        return node.value;
    }

    @Override
    public boolean containsKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        return map.containsKey(key);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        map.clear();
        head.next = tail;
        tail.prev = head;
    }

    // ======================== 链表操作 ========================

    /**
     * 将节点移到链表头部（标记为最近使用）。
     *
     * @param node 待移动节点
     */
    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addToHead(node);
    }

    /**
     * 在链表头部插入节点。
     *
     * @param node 待插入节点
     */
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * 从链表中删除节点。
     *
     * @param node 待删除节点
     */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * 淘汰链表尾部节点（最久未使用）。
     */
    private void evictTail() {
        Node<K, V> victim = tail.prev;
        if (victim == head) {
            return;
        }
        removeNode(victim);
        map.remove(victim.key);
    }

    // ======================== 内部节点 ========================

    /**
     * 双向链表节点。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static class Node<K, V> {

        /**
         * 缓存键，用于 HashMap 反向查找。
         */
        final K key;

        /**
         * 缓存值，可变。
         */
        V value;

        /**
         * 前驱节点。
         */
        Node<K, V> prev;

        /**
         * 后继节点。
         */
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
