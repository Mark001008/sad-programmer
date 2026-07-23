package com.sad.programmer.database.index;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * MySQL 索引面试测试。
 *
 * <p>通过 EXPLAIN 验证索引使用情况，覆盖最左前缀、覆盖索引、索引失效等考点。</p>
 */
public class IndexDemoTest {

    private IndexDemo demo;

    @Before
    public void setUp() throws Exception {
        demo = new IndexDemo();
        demo.initTable();
        demo.bulkInsert(5000);
    }

    @After
    public void tearDown() throws Exception {
        demo.dropTable();
    }

    // ======================== 聚簇索引 ========================

    /**
     * 验证主键查询走聚簇索引。
     */
    @Test
    public void shouldUseClusteredIndexForPrimaryKey() throws Exception {
        String key = demo.explainKey("SELECT * FROM order_demo WHERE id = 1");
        assertEquals("PRIMARY", key);
    }

    // ======================== 唯一索引 ========================

    /**
     * 验证唯一索引查询。
     */
    @Test
    public void shouldUseUniqueIndexForOrderNo() throws Exception {
        String key = demo.explainKey("SELECT * FROM order_demo WHERE order_no = 'ORD00000001'");
        assertEquals("uk_order_no", key);
    }

    // ======================== 最左前缀原则 ========================

    /**
     * 验证联合索引 (user_id, created_at) 的最左前缀：只用 user_id 也能命中索引。
     */
    @Test
    public void shouldUseLeftPrefixOfCompositeIndex() throws Exception {
        String key = demo.explainKey("SELECT * FROM order_demo WHERE user_id = 1");
        assertEquals("idx_user_created", key);
    }

    /**
     * 验证联合索引同时使用两个字段也能命中。
     */
    @Test
    public void shouldUseFullCompositeIndex() throws Exception {
        String key = demo.explainKey(
                "SELECT * FROM order_demo WHERE user_id = 1 AND created_at > '2026-01-01'");
        assertEquals("idx_user_created", key);
    }

    /**
     * 验证跳过最左前缀（只用 created_at）时不走联合索引。
     */
    @Test
    public void shouldNotUseCompositeIndexWhenSkippingLeftPrefix() throws Exception {
        String key = demo.explainKey(
                "SELECT * FROM order_demo WHERE created_at > '2026-01-01'");
        assertNotEquals("idx_user_created", key);
    }

    // ======================== 覆盖索引 ========================

    /**
     * 验证覆盖索引：查询字段全部在索引中，Extra 显示 Using index。
     */
    @Test
    public void shouldUseCoveringIndexWhenAllColumnsInIndex() throws Exception {
        String extra = demo.explainExtra(
                "SELECT user_id, created_at FROM order_demo WHERE user_id = 1");
        assertTrue("覆盖索引应显示 Using index", extra.contains("Using index"));
    }

    // ======================== 索引失效场景 ========================

    /**
     * 验证对索引列使用函数时索引失效。
     */
    @Test
    public void shouldNotUseIndexWhenApplyingFunction() throws Exception {
        // YEAR(created_at) 会导致索引失效
        String key = demo.explainKey(
                "SELECT * FROM order_demo WHERE YEAR(created_at) = 2026");
        // 不走 idx_user_created 的 created_at 部分
        assertNull("对索引列使用函数应导致索引失效", key);
    }

    /**
     * 验证 LIKE '%xx' 前缀通配符导致索引失效。
     */
    @Test
    public void shouldNotUseIndexForLikeWithLeadingWildcard() throws Exception {
        String type = demo.explainType(
                "SELECT * FROM order_demo WHERE order_no LIKE '%0001'");
        assertEquals("前缀通配符应全表扫描", "ALL", type);
    }

    /**
     * 验证 LIKE 'xx%' 后缀通配符仍可使用索引。
     */
    @Test
    public void shouldUseIndexForLikeWithTrailingWildcard() throws Exception {
        String key = demo.explainKey(
                "SELECT * FROM order_demo WHERE order_no LIKE 'ORD0001%'");
        assertEquals("uk_order_no", key);
    }
}
