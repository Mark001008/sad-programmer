package com.sad.programmer.business.inventory.sales;

import com.sad.programmer.database.common.JdbcUtil;
import com.sad.programmer.redis.common.RedisUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * {@link SalesInventoryServiceImpl} 销售层库存服务集成测试类（真实 MySQL + Redis）。
 *
 * <p>覆盖：库存同步、查询、分配、回收的正常路径和异常路径，
 * 包括单商品/批量查询、渠道默认值处理、库存不足回退、
 * 参数校验异常等场景。每个测试方法使用 UUID 前缀隔离测试数据，
 * 在 {@code @Before} 中建表并初始化，在 {@code @After} 中删表并清理 Redis。</p>
 *
 * <p>注意：本测试需要连接远程 MySQL 和 Redis，需在沙箱外运行。</p>
 */
public class SalesInventoryServiceImplTest {

    /**
     * UUID 前缀基数，用于生成隔离的商品ID，避免不同测试运行之间的 key 冲突。
     */
    private static final long UUID_BASE;

    /**
     * 测试用商品ID，基于 UUID 前缀生成，保证全局唯一。
     */
    private static final long PRODUCT_ID;

    /**
     * 测试用第二商品ID，基于 UUID 前缀生成，保证全局唯一。
     */
    private static final long PRODUCT_ID_2;

    static {
        // 取 UUID 前 8 位十六进制转为 long，避免负数
        UUID_BASE = Math.abs((long) UUID.randomUUID().hashCode() * 1000L);
        PRODUCT_ID = UUID_BASE + 1;
        PRODUCT_ID_2 = UUID_BASE + 2;
    }

    /**
     * 测试用订单号常量。
     */
    private static final String ORDER_ID = "ORD-TEST-001";

    /**
     * 测试用渠道名称常量。
     */
    private static final String CHANNEL = "TMALL";

    /**
     * 建表 SQL，使用 DROP IF EXISTS + CREATE TABLE 保证幂等。
     */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS sales_inventory ("
                    + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "  product_id BIGINT NOT NULL,"
                    + "  available_stock INT NOT NULL DEFAULT 0,"
                    + "  allocated_stock INT NOT NULL DEFAULT 0,"
                    + "  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "  PRIMARY KEY (id),"
                    + "  UNIQUE KEY uk_product_id (product_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    /**
     * 删表 SQL。
     */
    private static final String DROP_TABLE_SQL = "DROP TABLE IF EXISTS sales_inventory";

    /**
     * 销售库存数据访问对象。
     */
    private SalesInventoryDao dao;

    /**
     * 被测销售层库存服务实例，每个测试方法前重新创建。
     */
    private SalesInventoryServiceImpl service;

    /**
     * 数据库连接，用于执行 DDL 语句。
     */
    private Connection conn;

    /**
     * Redis 连接，用于清理测试数据。
     */
    private Jedis jedis;

    /**
     * 初始化测试前置环境：建表、创建 DAO 和 Service 实例。
     *
     * <p>每个测试方法执行前调用，保证测试之间互不干扰。</p>
     */
    @Before
    public void setUp() throws Exception {
        // 获取数据库连接并建表
        conn = JdbcUtil.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(DROP_TABLE_SQL);
            stmt.execute(CREATE_TABLE_SQL);
        }

        // 创建 DAO 和 Service 实例
        dao = new SalesInventoryDaoImpl();
        service = new SalesInventoryServiceImpl(dao);

        // 获取 Redis 连接，清理可能残留的测试数据
        jedis = RedisUtil.getResource();
        jedis.del(SalesInventoryCacheKey.salesStockKey(PRODUCT_ID));
        jedis.del(SalesInventoryCacheKey.salesStockLockKey(PRODUCT_ID));
        jedis.del(SalesInventoryCacheKey.salesStockKey(PRODUCT_ID_2));
        jedis.del(SalesInventoryCacheKey.salesStockLockKey(PRODUCT_ID_2));
    }

    /**
     * 清理测试后置环境：删表、清理 Redis key、关闭资源。
     *
     * <p>每个测试方法执行后调用，释放数据库和 Redis 资源。</p>
     */
    @After
    public void tearDown() throws Exception {
        // 清理 Redis 中的测试 key
        if (jedis != null) {
            jedis.del(SalesInventoryCacheKey.salesStockKey(PRODUCT_ID));
            jedis.del(SalesInventoryCacheKey.salesStockLockKey(PRODUCT_ID));
            jedis.del(SalesInventoryCacheKey.salesStockKey(PRODUCT_ID_2));
            jedis.del(SalesInventoryCacheKey.salesStockLockKey(PRODUCT_ID_2));
            RedisUtil.returnResource(jedis);
            jedis = null;
        }

        // 删表并关闭数据库连接
        if (conn != null && !conn.isClosed()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(DROP_TABLE_SQL);
            }
            JdbcUtil.close(conn);
            conn = null;
        }

        service = null;
        dao = null;
    }

    // ======================== 正常路径：同步 + 查询 ========================

    /**
     * 验证 syncActualStock 后查询能获取到正确的库存数量。
     *
     * <p>操作：syncActualStock(PRODUCT_ID, 100) → queryAvailableStock(PRODUCT_ID, "TMALL")。
     * 预期：查询结果的可用库存为 100，商品ID正确。</p>
     */
    @Test
    public void shouldSyncAndQueryStockSuccessfully() {
        // 同步100件实际库存到 MySQL + Redis
        service.syncActualStock(PRODUCT_ID, 100);

        // 查询渠道可销售库存
        SalesInventoryResult result = service.queryAvailableStock(PRODUCT_ID, CHANNEL);

        assertNotNull("查询结果不应为null", result);
        assertEquals("商品ID应匹配", PRODUCT_ID, result.getProductId());
        assertEquals("同步后可用库存应为100", 100, result.getAvailableStock());
    }

    // ======================== 正常路径：分配 ========================

    /**
     * 验证库存充足时分配成功，且查询结果反映正确的剩余库存。
     *
     * <p>操作：syncActualStock(100) → allocateStock(30) → queryAvailableStock。
     * 预期：分配返回 true，查询剩余可用库存为 70。</p>
     */
    @Test
    public void shouldAllocateStockSuccessfullyWhenSufficient() {
        // 同步100件库存
        service.syncActualStock(PRODUCT_ID, 100);

        // 分配30件
        boolean allocated = service.allocateStock(ORDER_ID, PRODUCT_ID, 30, CHANNEL);

        // 验证分配成功
        assertTrue("库存充足时分配应成功", allocated);

        // 查询剩余库存
        SalesInventoryResult result = service.queryAvailableStock(PRODUCT_ID, CHANNEL);
        assertEquals("分配30后剩余库存应为70", 70, result.getAvailableStock());
    }

    /**
     * 验证库存不足时分配失败，且库存不发生变化。
     *
     * <p>操作：syncActualStock(10) → allocateStock(20)。
     * 预期：分配返回 false，库存保持原值不变。</p>
     */
    @Test
    public void shouldFailAllocateStockWhenInsufficient() {
        // 同步10件库存
        service.syncActualStock(PRODUCT_ID, 10);

        // 尝试分配20件，库存不足
        boolean allocated = service.allocateStock(ORDER_ID, PRODUCT_ID, 20, CHANNEL);

        // 验证分配失败
        assertFalse("库存不足时分配应失败", allocated);

        // 验证库存未发生变化
        SalesInventoryResult result = service.queryAvailableStock(PRODUCT_ID, CHANNEL);
        assertEquals("分配失败后库存应保持原值", 10, result.getAvailableStock());
    }

    // ======================== 正常路径：回收 ========================

    /**
     * 验证分配后回收库存能正确恢复可销售数量。
     *
     * <p>操作：syncActualStock(100) → allocateStock(30) → reclaimStock(20)。
     * 预期：回收返回 true，查询剩余可用库存为 90。</p>
     */
    @Test
    public void shouldReclaimStockSuccessfully() {
        // 同步100件库存
        service.syncActualStock(PRODUCT_ID, 100);

        // 分配30件
        boolean allocated = service.allocateStock(ORDER_ID, PRODUCT_ID, 30, CHANNEL);
        assertTrue("分配应成功", allocated);

        // 回收20件
        boolean reclaimed = service.reclaimStock(ORDER_ID, PRODUCT_ID, 20, CHANNEL);

        // 验证回收成功
        assertTrue("回收库存应成功", reclaimed);

        // 查询剩余库存：100 - 30 + 20 = 90
        SalesInventoryResult result = service.queryAvailableStock(PRODUCT_ID, CHANNEL);
        assertEquals("回收20后可用库存应为90", 90, result.getAvailableStock());
    }

    /**
     * 验证对未同步的商品回收库存时返回 false。
     *
     * <p>操作：不调用 syncActualStock，直接 reclaimStock。
     * 预期：回收返回 false，因为数据库中不存在该商品的库存记录。</p>
     */
    @Test
    public void shouldFailReclaimWhenNoStockEntry() {
        // 未同步商品库存，直接尝试回收
        boolean reclaimed = service.reclaimStock(ORDER_ID, PRODUCT_ID, 10, CHANNEL);

        // 验证回收失败
        assertFalse("未同步的商品回收应返回false", reclaimed);
    }

    // ======================== 正常路径：渠道默认值 ========================

    /**
     * 验证 allocateStock 的 channel 参数为 null 时，使用 "DEFAULT" 作为渠道名。
     *
     * <p>操作：syncActualStock(100) → allocateStock(channel=null) → queryAvailableStock(channel="DEFAULT")。
     * 预期：分配成功，"DEFAULT" 渠道查询到已分配的库存变化。</p>
     */
    @Test
    public void shouldUseDefaultChannelWhenChannelIsNull() {
        // 同步100件库存
        service.syncActualStock(PRODUCT_ID, 100);

        // channel 为 null，应自动使用 "DEFAULT"
        boolean allocated = service.allocateStock(ORDER_ID, PRODUCT_ID, 30, null);

        // 验证分配成功
        assertTrue("channel=null时应使用DEFAULT渠道并分配成功", allocated);

        // 查询 DEFAULT 渠道的库存，验证分配已生效
        SalesInventoryResult result = service.queryAvailableStock(PRODUCT_ID, "DEFAULT");
        assertNotNull("DEFAULT渠道查询结果不应为null", result);
        Map<String, Integer> channelStock = result.getChannelStock();
        assertTrue("渠道库存分布应包含DEFAULT", channelStock.containsKey("DEFAULT"));
        assertEquals("DEFAULT渠道分配30后应为70", Integer.valueOf(70), channelStock.get("DEFAULT"));
    }

    // ======================== 正常路径：批量查询 ========================

    /**
     * 验证批量查询多个商品时，返回结果与输入顺序一致且数据正确。
     *
     * <p>操作：同步商品1(100)和商品2(200) → batchQuery([商品1, 商品2])。
     * 预期：结果列表大小为 2，顺序与输入一致，库存数量正确。</p>
     */
    @Test
    public void shouldBatchQueryMultipleProducts() {
        // 分别同步两个商品的库存
        service.syncActualStock(PRODUCT_ID, 100);
        service.syncActualStock(PRODUCT_ID_2, 200);

        // 批量查询
        List<Long> productIds = Arrays.asList(PRODUCT_ID, PRODUCT_ID_2);
        List<SalesInventoryResult> results = service.batchQueryAvailableStock(productIds, CHANNEL);

        // 验证结果数量
        assertNotNull("批量查询结果不应为null", results);
        assertEquals("结果数量应与输入一致", 2, results.size());

        // 验证第一个商品
        SalesInventoryResult first = results.get(0);
        assertEquals("第一个结果商品ID应匹配", PRODUCT_ID, first.getProductId());
        assertEquals("第一个商品可用库存应为100", 100, first.getAvailableStock());

        // 验证第二个商品
        SalesInventoryResult second = results.get(1);
        assertEquals("第二个结果商品ID应匹配", PRODUCT_ID_2, second.getProductId());
        assertEquals("第二个商品可用库存应为200", 200, second.getAvailableStock());
    }

    // ======================== 异常路径：参数校验 ========================

    /**
     * 验证传入非法商品ID（0 或负数）时抛出 IllegalArgumentException。
     *
     * <p>参数：productId = 0（边界值）。
     * 预期：抛出 IllegalArgumentException。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenProductIdIsInvalid() {
        service.queryAvailableStock(0, CHANNEL);
    }

    /**
     * 验证 queryAvailableStock 传入 null 渠道时抛出 IllegalArgumentException。
     *
     * <p>参数：channel = null。
     * 预期：抛出 IllegalArgumentException。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenChannelIsNullForQuery() {
        service.queryAvailableStock(PRODUCT_ID, null);
    }

    /**
     * 验证 allocateStock 传入 null 订单号时抛出 IllegalArgumentException。
     *
     * <p>参数：orderId = null。
     * 预期：抛出 IllegalArgumentException。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenOrderIdIsNull() {
        service.allocateStock(null, PRODUCT_ID, 10, CHANNEL);
    }

    /**
     * 验证 allocateStock 传入 quantity = 0 时抛出 IllegalArgumentException。
     *
     * <p>参数：quantity = 0（不满足大于0的约束）。
     * 预期：抛出 IllegalArgumentException。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenQuantityIsZero() {
        service.allocateStock(ORDER_ID, PRODUCT_ID, 0, CHANNEL);
    }

    /**
     * 验证 syncActualStock 传入负数数量时抛出 IllegalArgumentException。
     *
     * <p>参数：quantity = -1。
     * 预期：抛出 IllegalArgumentException。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenSyncQuantityIsNegative() {
        service.syncActualStock(PRODUCT_ID, -1);
    }
}
