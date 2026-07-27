package com.sad.programmer.business.inventory.scheduling;

import com.sad.programmer.database.common.JdbcUtil;
import com.sad.programmer.redis.common.RedisUtil;
import com.sad.programmer.redis.delay.DelayQueueImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 调度层库存服务实现类集成测试（真实 MySQL + Redis）。
 *
 * <p>覆盖 {@link SchedulingInventoryServiceImpl} 的完整生命周期操作：
 * 预占 → 锁定 → 确认扣减（或解锁/回滚），验证状态流转的正确性、幂等性、
 * 参数校验和异常边界场景。</p>
 *
 * <p>测试依赖：
 * <ul>
 *   <li>远程 MySQL 数据库（通过 {@link JdbcUtil} 获取连接）</li>
 *   <li>远程 Redis 实例（通过 {@link DelayQueueImpl} 操作延迟队列）</li>
 * </ul></p>
 *
 * <p>数据隔离策略：
 * <ul>
 *   <li>每个测试方法执行前重建 reservation_record 表</li>
 *   <li>每个测试方法使用独立的 UUID 前缀隔离业务数据</li>
 *   <li>每个测试方法使用独立的 Redis ZSET key，tearDown 时删除</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public class SchedulingInventoryServiceImplTest {

    /** 被测的调度层库存服务实例。 */
    private SchedulingInventoryServiceImpl service;

    /** Redis 延迟队列实例，用于测试数据清理。 */
    private DelayQueueImpl delayQueue;

    /** Redis ZSET 键名，使用 UUID 保证测试隔离。 */
    private String queueKey;

    /** UUID 前缀，用于隔离测试数据。 */
    private String testPrefix;

    /** 测试用商品ID。 */
    private static final long PRODUCT_ID = 10001L;

    /** 测试用预占数量。 */
    private static final int QUANTITY = 5;

    /** 测试用过期时长（毫秒），10 秒足够大多数测试完成。 */
    private static final long EXPIRE_MILLIS = 10_000L;

    /** 测试用支付流水ID。 */
    private static final String PAYMENT_ID = "PAY_001";

    /** 测试用解锁/回滚原因。 */
    private static final String REASON = "user cancelled";

    /**
     * 建表 SQL：创建 reservation_record 表。
     */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE reservation_record ("
            + "  id BIGINT NOT NULL AUTO_INCREMENT,"
            + "  reservation_id VARCHAR(64) NOT NULL,"
            + "  order_id VARCHAR(64) NOT NULL,"
            + "  product_id BIGINT NOT NULL,"
            + "  quantity INT NOT NULL,"
            + "  status TINYINT NOT NULL DEFAULT 0,"
            + "  expire_time DATETIME,"
            + "  payment_id VARCHAR(64),"
            + "  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "  PRIMARY KEY (id),"
            + "  UNIQUE KEY uk_reservation_id (reservation_id),"
            + "  KEY idx_order_id (order_id),"
            + "  KEY idx_order_product (order_id, product_id),"
            + "  KEY idx_product_id (product_id),"
            + "  KEY idx_status_expire (status, expire_time)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    /**
     * 每个测试方法执行前：建表 + 创建新的 service 实例。
     *
     * <p>流程：
     * <ol>
     *   <li>生成 UUID 前缀用于测试数据隔离</li>
     *   <li>生成 UUID 后缀的 Redis 队列 key</li>
     *   <li>通过 {@link JdbcUtil} 获取连接，执行 DROP TABLE IF EXISTS + CREATE TABLE</li>
     *   <li>创建 {@link ReservationDaoImpl} 和 {@link DelayQueueImpl} 实例</li>
     *   <li>组装 {@link SchedulingInventoryServiceImpl}</li>
     * </ol></p>
     */
    @Before
    public void setUp() {
        testPrefix = UUID.randomUUID().toString().substring(0, 8) + "-";
        queueKey = "scheduling:test:" + UUID.randomUUID().toString().substring(0, 8);
        delayQueue = new DelayQueueImpl(queueKey);

        Connection conn = null;
        Statement stmt = null;
        try {
            conn = JdbcUtil.getConnection();
            stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS reservation_record");
            stmt.execute(CREATE_TABLE_SQL);
        } catch (Exception e) {
            throw new RuntimeException("建表失败", e);
        } finally {
            JdbcUtil.close(stmt, conn);
        }

        ReservationDaoImpl reservationDao = new ReservationDaoImpl();
        service = new SchedulingInventoryServiceImpl(reservationDao, delayQueue);
    }

    /**
     * 每个测试方法执行后：删表 + 清理 Redis 测试数据。
     *
     * <p>流程：
     * <ol>
     *   <li>通过 {@link JdbcUtil} 获取连接，执行 DROP TABLE IF EXISTS</li>
     *   <li>通过 {@link RedisUtil} 获取 Jedis 连接，删除测试用 ZSET key</li>
     *   <li>置空引用便于 GC 回收</li>
     * </ol></p>
     */
    @After
    public void tearDown() {
        // 删表
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = JdbcUtil.getConnection();
            stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS reservation_record");
        } catch (Exception e) {
            // 忽略清理异常
        } finally {
            JdbcUtil.close(stmt, conn);
        }

        // 清理 Redis 测试数据
        Jedis jedis = null;
        try {
            jedis = RedisUtil.getResource();
            jedis.del(queueKey);
        } catch (Exception e) {
            // 忽略清理异常
        } finally {
            RedisUtil.returnResource(jedis);
        }

        service = null;
        delayQueue = null;
    }

    // ======================== 预占测试 ========================

    /**
     * 验证使用合法参数预占库存时，返回成功且 reservationId 非空。
     *
     * <p>预期行为：预占成功，返回包含有效 reservationId 的成功结果。</p>
     */
    @Test
    public void shouldReserveStockSuccessfullyWhenValidParams() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult result = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);

        assertTrue("预占应成功", result.isSuccess());
        assertNotNull("reservationId 不应为 null", result.getReservationId());
        assertFalse("reservationId 不应为空字符串", result.getReservationId().trim().isEmpty());
    }

    /**
     * 验证同一订单对同一商品重复预占时，第二次请求返回失败（幂等保护）。
     *
     * <p>预期行为：首次预占成功，重复预占返回失败结果，包含重复提示消息。</p>
     */
    @Test
    public void shouldFailReserveWhenDuplicateOrderAndProduct() {
        String orderId = testPrefix + "ORD-001";

        // 第一次预占成功
        ReservationResult first = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("首次预占应成功", first.isSuccess());

        // 第二次相同 orderId + productId 预占失败（幂等检查）
        ReservationResult second = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertFalse("重复预占应失败", second.isSuccess());
        assertNotNull("失败消息不应为 null", second.getMessage());
    }

    // ======================== 锁定测试 ========================

    /**
     * 验证处于 RESERVED 状态的预占记录可以成功锁定为 LOCKED 状态。
     *
     * <p>预期行为：预占成功后调用锁定，返回 true，状态从 RESERVED 变为 LOCKED。</p>
     */
    @Test
    public void shouldLockStockSuccessfullyWhenReserved() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        boolean locked = service.lockStock(reserveResult.getReservationId(), PAYMENT_ID);
        assertTrue("预占→锁定应成功", locked);
    }

    /**
     * 验证预占过期后调用锁定，返回 false。
     *
     * <p>使用 expireMillis=1 制造极短过期时间，Thread.sleep(50) 等待过期后验证锁定失败。
     * lockStock 内部检查 System.currentTimeMillis() > expireTimeMillis，
     * 过期时自动将状态标记为 UNLOCKED 并返回 false。</p>
     *
     * @throws InterruptedException 线程休眠被中断时抛出
     */
    @Test
    public void shouldFailLockWhenReservationExpired() throws InterruptedException {
        String orderId = testPrefix + "ORD-001";

        // 使用 1 毫秒过期时间
        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, 1L);
        assertTrue("预占应成功", reserveResult.isSuccess());

        // 等待超过过期时间
        Thread.sleep(50);

        boolean locked = service.lockStock(reserveResult.getReservationId(), PAYMENT_ID);
        assertFalse("过期后锁定应失败", locked);
    }

    /**
     * 验证使用不存在的 reservationId 调用锁定时，返回 false 而非抛出异常。
     *
     * <p>预期行为：查询不到记录，返回 false，不产生异常。</p>
     */
    @Test
    public void shouldFailLockWhenReservationNotFound() {
        boolean locked = service.lockStock("R_NONEXISTENT_" + testPrefix, PAYMENT_ID);
        assertFalse("不存在的预占ID锁定应返回 false", locked);
    }

    // ======================== 解锁测试 ========================

    /**
     * 验证处于 RESERVED 状态的预占记录可以成功解锁。
     *
     * <p>预期行为：预占成功后调用解锁，返回 true，状态从 RESERVED 变为 UNLOCKED。</p>
     */
    @Test
    public void shouldUnlockStockSuccessfullyWhenReserved() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        boolean unlocked = service.unlockStock(reserveResult.getReservationId(), REASON);
        assertTrue("预占→解锁应成功", unlocked);
    }

    /**
     * 验证处于 LOCKED 状态的预占记录可以成功解锁。
     *
     * <p>预期行为：预占 → 锁定 → 解锁完整链路，最终状态为 UNLOCKED。</p>
     */
    @Test
    public void shouldUnlockStockSuccessfullyWhenLocked() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        String reservationId = reserveResult.getReservationId();

        // 锁定
        boolean locked = service.lockStock(reservationId, PAYMENT_ID);
        assertTrue("预占→锁定应成功", locked);

        // 解锁
        boolean unlocked = service.unlockStock(reservationId, REASON);
        assertTrue("锁定→解锁应成功", unlocked);
    }

    /**
     * 验证已经处于 UNLOCKED 状态的预占记录再次解锁时返回 false。
     *
     * <p>预期行为：首次解锁成功，第二次解锁失败（状态已为 UNLOCKED，CAS 不匹配）。</p>
     */
    @Test
    public void shouldFailUnlockWhenAlreadyUnlocked() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        String reservationId = reserveResult.getReservationId();

        // 第一次解锁成功
        boolean firstUnlock = service.unlockStock(reservationId, REASON);
        assertTrue("首次解锁应成功", firstUnlock);

        // 第二次解锁失败（已 UNLOCKED，CAS 状态不匹配）
        boolean secondUnlock = service.unlockStock(reservationId, REASON);
        assertFalse("已解锁再解锁应返回 false", secondUnlock);
    }

    // ======================== 确认扣减测试 ========================

    /**
     * 验证处于 LOCKED 状态的预占记录可以成功确认扣减。
     *
     * <p>预期行为：预占 → 锁定 → 确认扣减完整链路，最终状态为 CONFIRMED。</p>
     */
    @Test
    public void shouldConfirmDeductionSuccessfullyWhenLocked() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        String reservationId = reserveResult.getReservationId();

        // 锁定
        boolean locked = service.lockStock(reservationId, PAYMENT_ID);
        assertTrue("预占→锁定应成功", locked);

        // 确认扣减
        boolean confirmed = service.confirmDeduction(reservationId);
        assertTrue("锁定→确认扣减应成功", confirmed);
    }

    /**
     * 验证处于 RESERVED 状态（未锁定）的预占记录调用确认扣减时返回 false。
     *
     * <p>预期行为：confirmDeduction 要求状态为 LOCKED，RESERVED 状态直接返回 false。</p>
     */
    @Test
    public void shouldFailConfirmWhenNotLocked() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        // 未锁定直接确认应失败
        boolean confirmed = service.confirmDeduction(reserveResult.getReservationId());
        assertFalse("未锁定状态确认扣减应返回 false", confirmed);
    }

    // ======================== 回滚测试 ========================

    /**
     * 验证处于 RESERVED 状态的预占记录可以成功回滚。
     *
     * <p>预期行为：预占后直接回滚，返回 true，状态变为 UNLOCKED。</p>
     */
    @Test
    public void shouldRollbackStockSuccessfullyWhenReserved() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        boolean rolledBack = service.rollbackStock(reserveResult.getReservationId(), REASON);
        assertTrue("预占→回滚应成功", rolledBack);
    }

    /**
     * 验证处于 LOCKED 状态的预占记录可以成功回滚。
     *
     * <p>预期行为：预占 → 锁定 → 回滚，返回 true，状态变为 UNLOCKED。</p>
     */
    @Test
    public void shouldRollbackStockSuccessfullyWhenLocked() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        String reservationId = reserveResult.getReservationId();

        // 锁定
        boolean locked = service.lockStock(reservationId, PAYMENT_ID);
        assertTrue("预占→锁定应成功", locked);

        // 回滚
        boolean rolledBack = service.rollbackStock(reservationId, REASON);
        assertTrue("锁定→回滚应成功", rolledBack);
    }

    /**
     * 验证已确认扣减（CONFIRMED 状态）的预占记录不可回滚。
     *
     * <p>预期行为：预占 → 锁定 → 确认扣减 → 回滚，最后一步返回 false，
     * 因为 CONFIRMED 是终态，不可回滚。</p>
     */
    @Test
    public void shouldNotRollbackWhenConfirmed() {
        String orderId = testPrefix + "ORD-001";

        ReservationResult reserveResult = service.reserveStock(orderId, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
        assertTrue("预占应成功", reserveResult.isSuccess());

        String reservationId = reserveResult.getReservationId();

        // 锁定
        boolean locked = service.lockStock(reservationId, PAYMENT_ID);
        assertTrue("预占→锁定应成功", locked);

        // 确认扣减
        boolean confirmed = service.confirmDeduction(reservationId);
        assertTrue("锁定→确认应成功", confirmed);

        // 回滚应失败（已确认不可回滚）
        boolean rolledBack = service.rollbackStock(reservationId, REASON);
        assertFalse("已确认扣减回滚应返回 false", rolledBack);
    }

    // ======================== 参数校验测试 ========================

    /**
     * 验证 orderId 为 null 时抛出 {@link IllegalArgumentException}。
     *
     * <p>预期行为：reserveStock 内部参数校验拦截 null orderId，抛出异常。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenOrderIdIsNull() {
        service.reserveStock(null, PRODUCT_ID, QUANTITY, EXPIRE_MILLIS);
    }

    /**
     * 验证 quantity 为 0 时抛出 {@link IllegalArgumentException}。
     *
     * <p>预期行为：reserveStock 内部参数校验拦截非法 quantity，抛出异常。</p>
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenQuantityIsZero() {
        String orderId = testPrefix + "ORD-001";
        service.reserveStock(orderId, PRODUCT_ID, 0, EXPIRE_MILLIS);
    }
}
