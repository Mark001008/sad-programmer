package com.sad.programmer.business.inventory.warehouse;

import com.sad.programmer.database.common.JdbcUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WarehouseInventoryServiceImpl} 的集成测试（真实 MySQL 连接）。
 *
 * <p>通过 {@link JdbcUtil} 获取远程 MySQL 连接，验证仓库层库存服务的入库、出库、查询、
 * 锁定/解锁、增加库存等核心功能，覆盖正常路径、边界条件、异常路径和并发安全场景。
 * 每个测试方法执行前建表、执行后删表，保证测试之间互不影响。</p>
 */
public class WarehouseInventoryServiceImplTest {

    /**
     * 被测仓库库存服务实例。
     */
    private WarehouseInventoryServiceImpl service;

    /**
     * 测试用仓库 ID。
     */
    private static final long WAREHOUSE_ID = 1L;

    /**
     * 测试用商品 ID。
     */
    private static final long PRODUCT_ID = 100L;

    /**
     * 测试用批次号。
     */
    private static final String BATCH_NO = "BATCH-001";

    /**
     * 测试用供应商 ID。
     */
    private static final String SUPPLIER_ID = "SUP-001";

    /**
     * 测试用订单 ID。
     */
    private static final String ORDER_ID = "ORD-001";

    /**
     * 建表 SQL，每次测试前执行以重建 warehouse_inventory 表。
     */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS warehouse_inventory (\n"
            + "  id BIGINT NOT NULL AUTO_INCREMENT,\n"
            + "  warehouse_id BIGINT NOT NULL,\n"
            + "  product_id BIGINT NOT NULL,\n"
            + "  total_stock INT NOT NULL DEFAULT 0,\n"
            + "  available_stock INT NOT NULL DEFAULT 0,\n"
            + "  locked_stock INT NOT NULL DEFAULT 0,\n"
            + "  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
            + "  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n"
            + "  PRIMARY KEY (id),\n"
            + "  UNIQUE KEY uk_warehouse_product (warehouse_id, product_id),\n"
            + "  KEY idx_product_id (product_id)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

    /**
     * 删表 SQL，每次测试后执行以清理测试数据。
     */
    private static final String DROP_TABLE_SQL = "DROP TABLE IF EXISTS warehouse_inventory";

    /**
     * 每个测试方法执行前重建表并创建新的服务实例，保证测试之间互不影响。
     *
     * @throws SQLException 当建表操作失败时抛出
     */
    @Before
    public void setUp() throws SQLException {
        initTable();
        service = new WarehouseInventoryServiceImpl();
    }

    /**
     * 每个测试方法执行后删除表，清理测试数据。
     *
     * @throws SQLException 当删表操作失败时抛出
     */
    @After
    public void tearDown() throws SQLException {
        dropTable();
    }

    /**
     * 初始化 warehouse_inventory 表：先删后建，确保表结构干净。
     *
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    private void initTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, DROP_TABLE_SQL);
            JdbcUtil.execute(conn, CREATE_TABLE_SQL);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 删除 warehouse_inventory 表。
     *
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    private void dropTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, DROP_TABLE_SQL);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 验证合法参数入库成功后，查询库存结果为 total=100, available=100, locked=0。
     */
    @Test
    public void shouldInboundSuccessfullyWhenValidParams() {
        // 执行入库操作
        InboundResult result = service.inbound(WAREHOUSE_ID, PRODUCT_ID, 100, BATCH_NO, SUPPLIER_ID);

        // 验证入库结果
        assertTrue("入库操作应该成功", result.isSuccess());
        assertNotNull("入库单号不应为空", result.getInboundId());

        // 查询入库后的库存
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应为100", 100, stock.getTotalStock());
        assertEquals("可用库存应为100", 100, stock.getAvailableStock());
        assertEquals("锁定库存应为0", 0, stock.getLockedStock());
    }

    /**
     * 验证对同一仓库的同一商品执行两次入库操作后，库存正确累加为80。
     */
    @Test
    public void shouldAccumulateStockWhenInboundSameProductTwice() {
        // 第一次入库 50 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 50, "BATCH-001", SUPPLIER_ID);
        // 第二次入库 30 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 30, "BATCH-002", SUPPLIER_ID);

        // 查询累加后的库存
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应为80（50+30）", 80, stock.getTotalStock());
        assertEquals("可用库存应为80", 80, stock.getAvailableStock());
        assertEquals("锁定库存应为0", 0, stock.getLockedStock());
    }

    /**
     * 验证查询不存在的库存记录时，返回全 0 的结果。
     */
    @Test
    public void shouldQueryZeroStockWhenNoRecord() {
        // 查询从未入库的商品
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, 999L);

        assertEquals("总库存应为0", 0, stock.getTotalStock());
        assertEquals("可用库存应为0", 0, stock.getAvailableStock());
        assertEquals("锁定库存应为0", 0, stock.getLockedStock());
    }

    /**
     * 验证批量查询多个商品时，返回结果列表的顺序和内容与输入一致。
     */
    @Test
    public void shouldBatchQueryMultipleProducts() {
        long productA = 201L;
        long productB = 202L;
        long productC = 203L;

        // 只对 productA 和 productB 入库，productC 无记录
        service.inbound(WAREHOUSE_ID, productA, 50, BATCH_NO, SUPPLIER_ID);
        service.inbound(WAREHOUSE_ID, productB, 80, BATCH_NO, SUPPLIER_ID);

        // 批量查询三个商品
        List<Long> productIds = Arrays.asList(productA, productB, productC);
        List<WarehouseInventoryResult> results = service.batchQueryWarehouseStock(WAREHOUSE_ID, productIds);

        assertEquals("结果数量应为3", 3, results.size());

        // 验证 productA
        assertEquals("productA 总库存应为50", 50, results.get(0).getTotalStock());
        assertEquals("productA 可用库存应为50", 50, results.get(0).getAvailableStock());

        // 验证 productB
        assertEquals("productB 总库存应为80", 80, results.get(1).getTotalStock());
        assertEquals("productB 可用库存应为80", 80, results.get(1).getAvailableStock());

        // 验证 productC 无记录，返回全 0
        assertEquals("productC 总库存应为0", 0, results.get(2).getTotalStock());
        assertEquals("productC 可用库存应为0", 0, results.get(2).getAvailableStock());
        assertEquals("productC 锁定库存应为0", 0, results.get(2).getLockedStock());
    }

    /**
     * 验证先入库再出库时，库存正确扣减且出库结果成功。
     */
    @Test
    public void shouldOutboundSuccessfullyWhenSufficientStock() {
        // 先入库 100 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 100, BATCH_NO, SUPPLIER_ID);

        // 出库 40 件
        OutboundResult result = service.outbound(WAREHOUSE_ID, PRODUCT_ID, 40, ORDER_ID);

        // 验证出库结果
        assertTrue("出库操作应该成功", result.isSuccess());
        assertNotNull("出库单号不应为空", result.getOutboundId());

        // 验证扣减后的库存
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应为60（100-40）", 60, stock.getTotalStock());
        assertEquals("可用库存应为60", 60, stock.getAvailableStock());
        assertEquals("锁定库存应为0", 0, stock.getLockedStock());
    }

    /**
     * 验证可用库存不足时，出库操作返回失败结果而不抛出异常。
     */
    @Test
    public void shouldFailOutboundWhenInsufficientStock() {
        // 先入库 20 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 20, BATCH_NO, SUPPLIER_ID);

        // 尝试出库 50 件，超过可用库存
        OutboundResult result = service.outbound(WAREHOUSE_ID, PRODUCT_ID, 50, ORDER_ID);

        // 验证出库失败
        assertFalse("出库操作应该失败", result.isSuccess());
        assertNotNull("失败消息不应为空", result.getMessage());

        // 验证库存未变化
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应仍为20", 20, stock.getTotalStock());
        assertEquals("可用库存应仍为20", 20, stock.getAvailableStock());
    }

    /**
     * 验证锁定库存后，可用库存减少、锁定库存增加，总库存不变。
     */
    @Test
    public void shouldLockStockSuccessfully() {
        // 先入库 100 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 100, BATCH_NO, SUPPLIER_ID);

        // 锁定 30 件
        service.lockStock(WAREHOUSE_ID, PRODUCT_ID, 30);

        // 验证锁定后的库存分布
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应仍为100", 100, stock.getTotalStock());
        assertEquals("可用库存应为70（100-30）", 70, stock.getAvailableStock());
        assertEquals("锁定库存应为30", 30, stock.getLockedStock());
    }

    /**
     * 验证锁定后再解锁，库存分布恢复到锁定前的状态。
     */
    @Test
    public void shouldUnlockStockSuccessfully() {
        // 先入库 100 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 100, BATCH_NO, SUPPLIER_ID);

        // 锁定 40 件
        service.lockStock(WAREHOUSE_ID, PRODUCT_ID, 40);

        // 解锁 40 件
        service.unlockStock(WAREHOUSE_ID, PRODUCT_ID, 40);

        // 验证解锁后库存恢复
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应仍为100", 100, stock.getTotalStock());
        assertEquals("可用库存应恢复为100", 100, stock.getAvailableStock());
        assertEquals("锁定库存应恢复为0", 0, stock.getLockedStock());
    }

    /**
     * 验证 increaseStock 直接增加总库存和可用库存。
     */
    @Test
    public void shouldIncreaseStockSuccessfully() {
        // 先入库 50 件
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 50, BATCH_NO, SUPPLIER_ID);

        // 再通过 increaseStock 增加 30 件
        service.increaseStock(WAREHOUSE_ID, PRODUCT_ID, 30);

        // 验证增加后的库存
        WarehouseInventoryResult stock = service.queryWarehouseStock(WAREHOUSE_ID, PRODUCT_ID);
        assertEquals("总库存应为80（50+30）", 80, stock.getTotalStock());
        assertEquals("可用库存应为80", 80, stock.getAvailableStock());
        assertEquals("锁定库存应为0", 0, stock.getLockedStock());
    }

    /**
     * 验证仓库 ID 小于等于 0 时，入库操作抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenWarehouseIdIsInvalid() {
        service.inbound(0L, PRODUCT_ID, 10, BATCH_NO, SUPPLIER_ID);
    }

    /**
     * 验证商品 ID 小于等于 0 时，入库操作抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenProductIdIsInvalid() {
        service.inbound(WAREHOUSE_ID, -1L, 10, BATCH_NO, SUPPLIER_ID);
    }

    /**
     * 验证入库数量为 0 时，操作抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenQuantityIsZero() {
        service.inbound(WAREHOUSE_ID, PRODUCT_ID, 0, BATCH_NO, SUPPLIER_ID);
    }

    /**
     * 验证并发出库场景下不会出现超卖现象。
     *
     * <p>入库 100 件商品，然后启动 100 个并发线程各出库 1 件，
     * 使用 {@link CountDownLatch} 控制所有线程同时出发。
     * 最终可用库存应大于等于 0，且所有出库结果中成功数量等于 100。</p>
     */
    @Test
    public void shouldHandleConcurrentOutboundWithoutOversell() {
        // 先入库 100 件
        final long concurrentWarehouseId = 10L;
        final long concurrentProductId = 999L;
        service.inbound(concurrentWarehouseId, concurrentProductId, 100, BATCH_NO, SUPPLIER_ID);

        // 并发出库配置
        final int threadCount = 100;
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);

        // 创建固定大小线程池
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            // 提交所有出库任务
            for (int i = 0; i < threadCount; i++) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 等待发令枪，确保所有线程同时出发
                            startGate.await();
                            OutboundResult result = service.outbound(
                                    concurrentWarehouseId, concurrentProductId, 1, "CONC-ORD");
                            if (result.isSuccess()) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    }
                });
            }

            // 发令枪响，所有线程同时开始出库
            startGate.countDown();

            // 等待所有线程执行完毕，最长等待 30 秒
            doneLatch.await();

            // 查询最终库存
            WarehouseInventoryResult stock = service.queryWarehouseStock(
                    concurrentWarehouseId, concurrentProductId);

            // 验证不超卖：最终可用库存不能为负数
            assertTrue("最终总库存不能为负数，实际: " + stock.getTotalStock(),
                    stock.getTotalStock() >= 0);
            assertTrue("最终可用库存不能为负数，实际: " + stock.getAvailableStock(),
                    stock.getAvailableStock() >= 0);
            assertTrue("最终锁定库存不能为负数，实际: " + stock.getLockedStock(),
                    stock.getLockedStock() >= 0);

            // 验证成功出库数量等于初始库存（100）
            assertEquals("成功出库数量应等于初始库存100", 100, successCount.get());

            // 验证总库存 = 可用库存 + 锁定库存
            assertEquals("总库存应等于可用库存加锁定库存",
                    stock.getAvailableStock() + stock.getLockedStock(), stock.getTotalStock());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 关闭线程池，确保资源释放
            executor.shutdownNow();
        }
    }
}
