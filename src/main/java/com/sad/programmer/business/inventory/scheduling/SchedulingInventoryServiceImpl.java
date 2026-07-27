package com.sad.programmer.business.inventory.scheduling;

import com.sad.programmer.database.common.JdbcUtil;
import com.sad.programmer.redis.delay.DelayQueue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 调度层库存服务实现类（MySQL + Redis 延迟队列投产版本）。
 *
 * <p>基于 MySQL 持久化预占记录，Redis 延迟队列实现过期自动解锁。
 * 通过 {@link ReservationDao} 操作数据库，CAS 方式保证并发状态流转安全；
 * 通过 {@link DelayQueue} 投递延迟消息，到期后自动触发解锁。</p>
 *
 * <p>状态常量定义：
 * <ul>
 *   <li>{@code STATUS_RESERVED = 0} - 预占中</li>
 *   <li>{@code STATUS_LOCKED = 1} - 已锁定</li>
 *   <li>{@code STATUS_UNLOCKED = 2} - 已解锁</li>
 *   <li>{@code STATUS_CONFIRMED = 3} - 已确认扣减</li>
 * </ul></p>
 *
 * <p>依赖注入：构造方法接收 {@link ReservationDao} 和 {@link DelayQueue}，
 * 不使用 Spring 框架，由调用方手动组装依赖。</p>
 *
 * @author sad-programmer
 */
public class SchedulingInventoryServiceImpl implements SchedulingInventoryService {

    /** 预占状态：预占中。 */
    public static final int STATUS_RESERVED = 0;

    /** 预占状态：已锁定。 */
    public static final int STATUS_LOCKED = 1;

    /** 预占状态：已解锁。 */
    public static final int STATUS_UNLOCKED = 2;

    /** 预占状态：已确认扣减。 */
    public static final int STATUS_CONFIRMED = 3;

    /** 预占记录数据访问对象。 */
    private final ReservationDao reservationDao;

    /** Redis 延迟队列，用于过期自动解锁。 */
    private final DelayQueue delayQueue;

    /**
     * 构造方法，注入 DAO 和延迟队列依赖。
     *
     * @param reservationDao 预占记录 DAO，不能为空
     * @param delayQueue     Redis 延迟队列，不能为空
     * @throws IllegalArgumentException 当任一参数为 null 时
     */
    public SchedulingInventoryServiceImpl(ReservationDao reservationDao, DelayQueue delayQueue) {
        if (reservationDao == null) {
            throw new IllegalArgumentException("reservationDao must not be null");
        }
        if (delayQueue == null) {
            throw new IllegalArgumentException("delayQueue must not be null");
        }
        this.reservationDao = reservationDao;
        this.delayQueue = delayQueue;
    }

    /**
     * 预占库存。
     *
     * <p>完整流程：
     * <ol>
     *   <li>参数校验</li>
     *   <li>获取数据库连接，开启事务</li>
     *   <li>幂等检查：查找同订单同商品的未解锁预占记录</li>
     *   <li>已存在则回滚事务并返回 failure</li>
     *   <li>生成唯一 reservationId（UUID）</li>
     *   <li>插入预占记录（status=RESERVED）</li>
     *   <li>提交事务</li>
     *   <li>投递延迟消息到 Redis 延迟队列（过期自动解锁）</li>
     * </ol></p>
     *
     * @param orderId      订单ID，不能为空
     * @param productId    商品ID，必须大于 0
     * @param quantity     预占数量，必须大于 0
     * @param expireMillis 预占过期时间（毫秒），必须大于 0
     * @return 预占结果，包含是否成功、预占ID或失败消息
     * @throws IllegalArgumentException 当参数不合法时
     */
    @Override
    public ReservationResult reserveStock(String orderId, long productId, int quantity, long expireMillis) {
        // 参数校验
        validateNotBlank(orderId, "orderId");
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be greater than 0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        if (expireMillis <= 0) {
            throw new IllegalArgumentException("expireMillis must be greater than 0");
        }

        Connection conn = null;
        try {
            // 获取连接并开启事务
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);

            // 幂等检查：同一订单对同一商品只能有一条非 UNLOCKED 状态的预占
            String existingId = reservationDao.findReservationIdByOrderProduct(conn, orderId, productId);
            if (existingId != null) {
                // 已存在未解锁的预占记录，回滚事务并返回失败
                conn.rollback();
                return ReservationResult.failure("duplicate reservation for order " + orderId
                        + " product " + productId + ", existing reservationId=" + existingId);
            }

            // 生成唯一预占ID
            String reservationId = generateReservationId();

            // 计算过期时间戳
            long expireTimeMillis = System.currentTimeMillis() + expireMillis;

            // 插入预占记录
            int rows = reservationDao.insert(conn, reservationId, orderId, productId,
                    quantity, STATUS_RESERVED, expireTimeMillis);
            if (rows != 1) {
                // 插入失败，回滚事务
                conn.rollback();
                return ReservationResult.failure("failed to insert reservation record");
            }

            // 提交事务
            conn.commit();

            // 投递延迟消息：expireMillis 后触发过期解锁
            delayQueue.offer(reservationId, expireMillis);

            return ReservationResult.success(reservationId);
        } catch (SQLException e) {
            // 数据库异常，尝试回滚
            rollbackQuietly(conn);
            return ReservationResult.failure("database error: " + e.getMessage());
        } finally {
            // 恢复自动提交并关闭连接
            closeQuietly(conn);
        }
    }

    /**
     * 锁定预占库存。
     *
     * <p>完整流程：
     * <ol>
     *   <li>参数校验</li>
     *   <li>获取数据库连接，开启事务</li>
     *   <li>查询预占记录，不存在返回 false</li>
     *   <li>状态不是 RESERVED 返回 false</li>
     *   <li>检查是否过期：过期则更新为 UNLOCKED 并返回 false</li>
     *   <li>CAS 更新状态 RESERVED → LOCKED，同时记录 paymentId</li>
     *   <li>提交事务</li>
     * </ol></p>
     *
     * @param reservationId 预占ID，不能为空
     * @param paymentId     支付流水ID，不能为空
     * @return true 表示锁定成功，false 表示锁定失败
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    @Override
    public boolean lockStock(String reservationId, String paymentId) {
        // 参数校验
        validateNotBlank(reservationId, "reservationId");
        validateNotBlank(paymentId, "paymentId");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);

            // 查询预占记录
            ReservationResult record = reservationDao.findById(conn, reservationId);
            if (record == null) {
                conn.rollback();
                return false;
            }

            // 状态必须是 RESERVED
            if (record.getStatus() != STATUS_RESERVED) {
                conn.rollback();
                return false;
            }

            // 检查是否过期：过期则标记 UNLOCKED 并返回 false
            if (System.currentTimeMillis() > record.getExpireTimeMillis()) {
                reservationDao.updateStatus(conn, reservationId, STATUS_RESERVED, STATUS_UNLOCKED);
                conn.commit();
                return false;
            }

            // CAS 更新状态 RESERVED → LOCKED，记录 paymentId
            int rows = reservationDao.updateStatusAndPaymentId(
                    conn, reservationId, STATUS_RESERVED, STATUS_LOCKED, paymentId);
            if (rows != 1) {
                // CAS 失败，说明状态已被其他线程修改
                conn.rollback();
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 解锁预占库存。
     *
     * <p>完整流程：
     * <ol>
     *   <li>参数校验</li>
     *   <li>获取数据库连接，开启事务</li>
     *   <li>查询预占记录，不存在返回 false</li>
     *   <li>状态不是 RESERVED 或 LOCKED 返回 false</li>
     *   <li>CAS 更新状态 → UNLOCKED</li>
     *   <li>提交事务</li>
     * </ol></p>
     *
     * @param reservationId 预占ID，不能为空
     * @param reason        解锁原因，不能为空，用于审计追踪
     * @return true 表示解锁成功，false 表示解锁失败
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    @Override
    public boolean unlockStock(String reservationId, String reason) {
        // 参数校验
        validateNotBlank(reservationId, "reservationId");
        validateNotBlank(reason, "reason");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);

            // 查询预占记录
            ReservationResult record = reservationDao.findById(conn, reservationId);
            if (record == null) {
                conn.rollback();
                return false;
            }

            // 只有 RESERVED 或 LOCKED 状态可以解锁
            int currentStatus = record.getStatus();
            if (currentStatus != STATUS_RESERVED && currentStatus != STATUS_LOCKED) {
                conn.rollback();
                return false;
            }

            // CAS 更新状态 → UNLOCKED
            int rows = reservationDao.updateStatus(conn, reservationId, currentStatus, STATUS_UNLOCKED);
            if (rows != 1) {
                conn.rollback();
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 确认扣减库存。
     *
     * <p>完整流程：
     * <ol>
     *   <li>参数校验</li>
     *   <li>获取数据库连接，开启事务</li>
     *   <li>查询预占记录，不存在返回 false</li>
     *   <li>状态不是 LOCKED 返回 false</li>
     *   <li>CAS 更新状态 LOCKED → CONFIRMED</li>
     *   <li>提交事务</li>
     * </ol></p>
     *
     * @param reservationId 预占ID，不能为空
     * @return true 表示确认成功，false 表示确认失败
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    @Override
    public boolean confirmDeduction(String reservationId) {
        // 参数校验
        validateNotBlank(reservationId, "reservationId");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);

            // 查询预占记录
            ReservationResult record = reservationDao.findById(conn, reservationId);
            if (record == null) {
                conn.rollback();
                return false;
            }

            // 状态必须是 LOCKED
            if (record.getStatus() != STATUS_LOCKED) {
                conn.rollback();
                return false;
            }

            // CAS 更新状态 LOCKED → CONFIRMED
            int rows = reservationDao.updateStatus(conn, reservationId, STATUS_LOCKED, STATUS_CONFIRMED);
            if (rows != 1) {
                conn.rollback();
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 回滚预占库存。
     *
     * <p>完整流程：
     * <ol>
     *   <li>参数校验</li>
     *   <li>获取数据库连接，开启事务</li>
     *   <li>查询预占记录，不存在返回 false</li>
     *   <li>状态是 CONFIRMED 返回 false（已确认不可回滚）</li>
     *   <li>CAS 更新状态 → UNLOCKED</li>
     *   <li>提交事务</li>
     * </ol></p>
     *
     * @param reservationId 预占ID，不能为空
     * @param reason        回滚原因，不能为空，用于审计追踪
     * @return true 表示回滚成功，false 表示回滚失败
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    @Override
    public boolean rollbackStock(String reservationId, String reason) {
        // 参数校验
        validateNotBlank(reservationId, "reservationId");
        validateNotBlank(reason, "reason");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);

            // 查询预占记录
            ReservationResult record = reservationDao.findById(conn, reservationId);
            if (record == null) {
                conn.rollback();
                return false;
            }

            // 已确认的记录不可回滚
            if (record.getStatus() == STATUS_CONFIRMED) {
                conn.rollback();
                return false;
            }

            // CAS 更新当前状态 → UNLOCKED
            int currentStatus = record.getStatus();
            int rows = reservationDao.updateStatus(conn, reservationId, currentStatus, STATUS_UNLOCKED);
            if (rows != 1) {
                conn.rollback();
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(conn);
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 处理过期预占记录。
     *
     * <p>从 Redis 延迟队列中拉取已到期的 reservationId，逐条执行 unlockStock 操作。
     * 该方法应由定时任务或调度器周期性调用，实现预占过期自动解锁。</p>
     *
     * <p>流程：
     * <ol>
     *   <li>调用 {@link DelayQueue#poll()} 取出一条到期消息</li>
     *   <li>若消息非空，调用 {@link #unlockStock} 执行解锁</li>
     *   <li>循环直到无到期消息</li>
     * </ol></p>
     */
    public void handleExpiredReservations() {
        // 循环拉取所有到期的预占消息
        String reservationId;
        while ((reservationId = delayQueue.poll()) != null) {
            try {
                // 执行解锁，reason 标记为自动过期
                unlockStock(reservationId, "auto-expired by delay queue");
            } catch (Exception e) {
                // 单条解锁失败不影响后续消息处理
                // 生产环境应记录日志或转入死信队列
            }
        }
    }

    /**
     * 生成唯一预占ID。
     *
     * <p>使用 UUID 去除横线生成，保证全局唯一性。</p>
     *
     * @return 32 位十六进制字符串格式的预占ID
     */
    private String generateReservationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 校验字符串参数不为 null 且不为空白。
     *
     * @param value     待校验的值
     * @param paramName 参数名称，用于错误消息
     * @throws IllegalArgumentException 当值为 null 或空白字符串时
     */
    private void validateNotBlank(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + " must not be null or empty");
        }
    }

    /**
     * 静默回滚事务，忽略回滚异常。
     *
     * @param conn 数据库连接，允许为 null
     */
    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 忽略回滚异常
            }
        }
    }

    /**
     * 恢复自动提交并关闭连接。
     *
     * <p>在 finally 块中调用，确保连接被正确归还。
     * 先恢复 autoCommit 为 true，再通过 {@link JdbcUtil#close} 关闭。</p>
     *
     * @param conn 数据库连接，允许为 null
     */
    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException ignored) {
                // 忽略恢复异常
            }
            JdbcUtil.close(conn);
        }
    }
}
