package com.sad.programmer.collection.lru;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link LRUCache} 面试实操测试。
 *
 * <p>覆盖：基本读写、容量淘汰、访问顺序更新、删除、边界条件、并发淘汰正确性。</p>
 */
public class LRUCacheTest {

    // ======================== 基本读写 ========================

    @Test
    public void shouldPutAndGetKeyValue() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1"));
    }

    @Test
    public void shouldReturnNullWhenKeyMissing() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        assertNull(cache.get("missing"));
    }

    @Test
    public void shouldOverwriteExistingKey() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.put("k1", "v2");
        assertEquals("v2", cache.get("k1"));
        assertEquals(1, cache.size());
    }

    // ======================== 容量淘汰 ========================

    @Test
    public void shouldEvictLeastRecentlyUsedWhenFull() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        // 容量已满，写入 k3 应淘汰最久未访问的 k1
        cache.put("k3", "v3");
        assertNull(cache.get("k1"));
        assertEquals("v2", cache.get("k2"));
        assertEquals("v3", cache.get("k3"));
        assertEquals(2, cache.size());
    }

    @Test
    public void shouldEvictCorrectlyAfterGetUpdatesOrder() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        // 访问 k1，使 k1 变为最近使用，k2 变为最久未使用
        cache.get("k1");
        // 写入 k3 应淘汰 k2 而非 k1
        cache.put("k3", "v3");
        assertEquals("v1", cache.get("k1"));
        assertNull(cache.get("k2"));
        assertEquals("v3", cache.get("k3"));
    }

    @Test
    public void shouldEvictCorrectlyAfterPutUpdatesOrder() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        // 更新 k1 的值，k1 变为最近使用
        cache.put("k1", "v1_new");
        // 写入 k3 应淘汰 k2
        cache.put("k3", "v3");
        assertEquals("v1_new", cache.get("k1"));
        assertNull(cache.get("k2"));
    }

    // ======================== 删除 ========================

    @Test
    public void shouldRemoveExistingKey() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        String removed = cache.remove("k1");
        assertEquals("v1", removed);
        assertNull(cache.get("k1"));
        assertEquals(0, cache.size());
    }

    @Test
    public void shouldReturnNullWhenRemoveMissingKey() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        assertNull(cache.remove("missing"));
    }

    // ======================== 包含判断 ========================

    @Test
    public void shouldReportContainsKey() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        assertTrue(cache.containsKey("k1"));
        assertFalse(cache.containsKey("k2"));
    }

    @Test
    public void shouldNotContainRemovedKey() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.remove("k1");
        assertFalse(cache.containsKey("k1"));
    }

    // ======================== 边界条件 ========================

    @Test
    public void shouldSupportCapacityOfOne() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(1);
        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1"));
        cache.put("k2", "v2");
        assertNull(cache.get("k1"));
        assertEquals("v2", cache.get("k2"));
        assertEquals(1, cache.size());
    }

    @Test
    public void shouldClearAllEntries() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(3);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("k1"));
        assertFalse(cache.containsKey("k2"));
    }

    // ======================== 参数校验 ========================

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullKeyOnGet() {
        new LRUCacheImpl<String, String>(2).get(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullKeyOnPut() {
        new LRUCacheImpl<String, String>(2).put(null, "v");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullValueOnPut() {
        new LRUCacheImpl<String, String>(2).put("k", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidCapacity() {
        new LRUCacheImpl<String, String>(0);
    }

    // ======================== 淘汰后重写 ========================

    @Test
    public void shouldAllowWritingEvictedKeyAgain() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(2);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3"); // k1 被淘汰
        assertNull(cache.get("k1"));
        cache.put("k1", "v1_new");
        assertEquals("v1_new", cache.get("k1"));
        assertEquals(2, cache.size());
    }

    // ======================== 持续淘汰正确性 ========================

    @Test
    public void shouldMaintainCorrectOrderUnderHeavyInserts() {
        LRUCache<Integer, Integer> cache = new LRUCacheImpl<>(3);
        for (int i = 1; i <= 100; i++) {
            cache.put(i, i);
        }
        // 只保留最后 3 个
        assertEquals(3, cache.size());
        assertEquals(Integer.valueOf(98), cache.get(98));
        assertEquals(Integer.valueOf(99), cache.get(99));
        assertEquals(Integer.valueOf(100), cache.get(100));
    }

    // ======================== 容量返回 ========================

    @Test
    public void shouldReturnCapacity() {
        LRUCache<String, String> cache = new LRUCacheImpl<>(5);
        assertEquals(5, cache.capacity());
    }
}
