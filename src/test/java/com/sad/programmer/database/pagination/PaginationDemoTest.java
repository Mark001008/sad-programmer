package com.sad.programmer.database.pagination;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * MySQL 分页查询面试测试。
 *
 * <p>覆盖：OFFSET 分页、游标分页、延迟关联优化。</p>
 */
public class PaginationDemoTest {

    private PaginationDemo demo;
    private static final int TOTAL = 10000;

    @Before
    public void setUp() throws Exception {
        demo = new PaginationDemo();
        demo.initTable();
        demo.bulkInsert(TOTAL);
    }

    @After
    public void tearDown() throws Exception {
        demo.dropTable();
    }

    // ======================== OFFSET 分页 ========================

    /**
     * 验证 OFFSET 分页首页正确。
     */
    @Test
    public void shouldReturnFirstPageWithOffset() throws Exception {
        List<Long> page1 = demo.offsetPagination(1, 10);
        assertEquals(10, page1.size());
        assertEquals(Long.valueOf(1), page1.get(0));
        assertEquals(Long.valueOf(10), page1.get(9));
    }

    /**
     * 验证 OFFSET 分页第二页正确。
     */
    @Test
    public void shouldReturnSecondPageWithOffset() throws Exception {
        List<Long> page2 = demo.offsetPagination(2, 10);
        assertEquals(10, page2.size());
        assertEquals(Long.valueOf(11), page2.get(0));
        assertEquals(Long.valueOf(20), page2.get(9));
    }

    /**
     * 验证 OFFSET 分页深度分页仍然正确。
     */
    @Test
    public void shouldReturnDeepPageWithOffset() throws Exception {
        List<Long> page1000 = demo.offsetPagination(1000, 10);
        assertEquals(10, page1000.size());
        assertEquals(Long.valueOf(9991), page1000.get(0));
    }

    // ======================== 游标分页 ========================

    /**
     * 验证游标分页首页正确。
     */
    @Test
    public void shouldReturnFirstPageWithCursor() throws Exception {
        List<Long> page1 = demo.cursorPagination(0, 10);
        assertEquals(10, page1.size());
        assertEquals(Long.valueOf(1), page1.get(0));
        assertEquals(Long.valueOf(10), page1.get(9));
    }

    /**
     * 验证游标分页连续翻页结果正确。
     */
    @Test
    public void shouldPaginateContinuouslyWithCursor() throws Exception {
        long lastId = 0;
        for (int page = 1; page <= 5; page++) {
            List<Long> ids = demo.cursorPagination(lastId, 10);
            assertEquals(10, ids.size());
            assertEquals(Long.valueOf((page - 1) * 10 + 1), ids.get(0));
            lastId = ids.get(ids.size() - 1);
        }
    }

    /**
     * 验证游标分页到最后一页数据不足 size。
     */
    @Test
    public void shouldReturnPartialResultsOnLastPage() throws Exception {
        // 跳到倒数第 5 条之前
        List<Long> lastPage = demo.cursorPagination(TOTAL - 5, 10);
        assertEquals(5, lastPage.size());
    }

    // ======================== 延迟关联 ========================

    /**
     * 验证延迟关联分页结果与 OFFSET 分页一致。
     */
    @Test
    public void shouldReturnSameResultsAsOffsetWithDeferredJoin() throws Exception {
        List<Long> offsetResult = demo.offsetPagination(100, 10);
        List<Long> deferredResult = demo.deferredJoinPagination(990, 10);
        assertEquals(offsetResult, deferredResult);
    }
}
