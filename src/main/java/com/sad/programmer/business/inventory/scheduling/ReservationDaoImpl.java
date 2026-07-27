package com.sad.programmer.business.inventory.scheduling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * 库存预占记录数据访问实现类。
 *
 * <p>基于原生 JDBC 操作 reservation_record 表，所有方法均使用传入的外部连接，
 * 由调用方控制事务提交与回滚。时间字段在 MySQL DATETIME 与 Java long 毫秒时间戳之间互转。</p>
 *
 * <p>表结构约定：
 * <ul>
 *   <li>reservation_id — VARCHAR，预占主键</li>
 *   <li>order_id — VARCHAR，关联订单</li>
 *   <li>product_id — BIGINT，商品ID</li>
 *   <li>quantity — INT，预占数量</li>
 *   <li>status — INT，状态（0=RESERVED, 1=LOCKED, 2=UNLOCKED, 3=CONFIRMED）</li>
 *   <li>payment_id — VARCHAR，支付流水ID（可为空）</li>
 *   <li>expire_time — DATETIME，过期时间</li>
 *   <li>create_time — DATETIME，创建时间</li>
 *   <li>update_time — DATETIME，更新时间</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public class ReservationDaoImpl implements ReservationDao {

    /** 表名常量，避免硬编码散落在各 SQL 中。 */
    private static final String TABLE_NAME = "reservation_record";

    /**
     * 根据预占ID查询预占记录。
     *
     * <p>执行 SELECT * 查询并将结果集映射为 {@link ReservationResult}。
     * DATETIME 类型的 expire_time 通过 {@link Timestamp#getTime()} 转换为毫秒时间戳。</p>
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @return 预占结果，未找到时返回 null
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 reservationId 为 null 或空字符串时
     */
    @Override
    public ReservationResult findById(Connection conn, String reservationId) throws SQLException {
        // 参数校验
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("reservationId must not be null or empty");
        }

        String sql = "SELECT reservation_id, order_id, product_id, quantity, status, "
                + "payment_id, expire_time FROM " + TABLE_NAME + " WHERE reservation_id = ?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setString(1, reservationId);
            rs = ps.executeQuery();
            if (!rs.next()) {
                // 记录不存在
                return null;
            }
            // 从结果集构建 ReservationResult 内部使用的数据
            String orderId = rs.getString("order_id");
            long productId = rs.getLong("product_id");
            int quantity = rs.getInt("quantity");
            int status = rs.getInt("status");
            String paymentId = rs.getString("payment_id");
            long expireTimeMillis = 0L;
            Timestamp expireTs = rs.getTimestamp("expire_time");
            if (expireTs != null) {
                expireTimeMillis = expireTs.getTime();
            }
            return new ReservationResult(
                    reservationId, orderId, productId, quantity, status, paymentId, expireTimeMillis);
        } finally {
            // 关闭 ResultSet 和 PreparedStatement
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    /**
     * 插入一条预占记录。
     *
     * <p>expireTimeMillis（Java 毫秒时间戳）通过 FROM_UNIXTIME(?/1000) 转换为 MySQL DATETIME。
     * create_time 和 update_time 使用 NOW() 由数据库生成。</p>
     *
     * @param conn            数据库连接，由调用方管理事务
     * @param reservationId   预占ID，不能为空
     * @param orderId         订单ID，不能为空
     * @param productId       商品ID，必须大于 0
     * @param quantity        预占数量，必须大于 0
     * @param status          初始状态值
     * @param expireTimeMillis 过期时间戳（毫秒），必须大于 0
     * @return 受影响行数，成功插入时返回 1
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当必填参数不合法时
     */
    @Override
    public int insert(Connection conn, String reservationId, String orderId, long productId,
                      int quantity, int status, long expireTimeMillis) throws SQLException {
        // 参数校验
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("reservationId must not be null or empty");
        }
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("orderId must not be null or empty");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be greater than 0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        if (expireTimeMillis <= 0) {
            throw new IllegalArgumentException("expireTimeMillis must be greater than 0");
        }

        String sql = "INSERT INTO " + TABLE_NAME
                + " (reservation_id, order_id, product_id, quantity, status, expire_time, create_time, update_time) "
                + "VALUES (?, ?, ?, ?, ?, FROM_UNIXTIME(? / 1000), NOW(), NOW())";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setString(1, reservationId);
            ps.setString(2, orderId);
            ps.setLong(3, productId);
            ps.setInt(4, quantity);
            ps.setInt(5, status);
            ps.setLong(6, expireTimeMillis);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * CAS 方式更新预占状态。
     *
     * <p>SQL 的 WHERE 子句包含 reservation_id AND status = fromStatus，
     * 利用数据库行锁实现乐观并发控制。仅当当前状态匹配时才更新为 toStatus。</p>
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @param fromStatus    期望的当前状态
     * @param toStatus      目标状态
     * @return 受影响行数，成功更新返回 1，状态不匹配返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 reservationId 为 null 或空字符串时
     */
    @Override
    public int updateStatus(Connection conn, String reservationId, int fromStatus, int toStatus)
            throws SQLException {
        // 参数校验
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("reservationId must not be null or empty");
        }

        String sql = "UPDATE " + TABLE_NAME
                + " SET status = ?, update_time = NOW() WHERE reservation_id = ? AND status = ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, toStatus);
            ps.setString(2, reservationId);
            ps.setInt(3, fromStatus);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * CAS 方式更新预占状态并记录支付流水ID。
     *
     * <p>在 updateStatus 基础上额外写入 payment_id 字段。
     * 用于锁定场景：RESERVED → LOCKED 时同时记录支付流水。</p>
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @param fromStatus    期望的当前状态
     * @param toStatus      目标状态
     * @param paymentId     支付流水ID，不能为空
     * @return 受影响行数，成功更新返回 1，状态不匹配返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当必填参数不合法时
     */
    @Override
    public int updateStatusAndPaymentId(Connection conn, String reservationId, int fromStatus,
                                         int toStatus, String paymentId) throws SQLException {
        // 参数校验
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("reservationId must not be null or empty");
        }
        if (paymentId == null || paymentId.trim().isEmpty()) {
            throw new IllegalArgumentException("paymentId must not be null or empty");
        }

        String sql = "UPDATE " + TABLE_NAME
                + " SET status = ?, payment_id = ?, update_time = NOW()"
                + " WHERE reservation_id = ? AND status = ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, toStatus);
            ps.setString(2, paymentId);
            ps.setString(3, reservationId);
            ps.setInt(4, fromStatus);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * 根据订单ID和商品ID查找未解锁的预占ID。
     *
     * <p>查询条件为 order_id = ? AND product_id = ? AND status != 2（UNLOCKED）。
     * 用于幂等检查：同一订单对同一商品只能有一条非 UNLOCKED 状态的预占记录。</p>
     *
     * @param conn      数据库连接，由调用方管理事务
     * @param orderId   订单ID，不能为空
     * @param productId 商品ID，必须大于 0
     * @return 匹配的预占ID，未找到返回 null
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 orderId 为 null 或空字符串时
     */
    @Override
    public String findReservationIdByOrderProduct(Connection conn, String orderId, long productId)
            throws SQLException {
        // 参数校验
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new IllegalArgumentException("orderId must not be null or empty");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("productId must be greater than 0");
        }

        String sql = "SELECT reservation_id FROM " + TABLE_NAME
                + " WHERE order_id = ? AND product_id = ? AND status != 2";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setString(1, orderId);
            ps.setLong(2, productId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("reservation_id");
            }
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    /**
     * 静默关闭可关闭资源，忽略关闭异常。
     *
     * <p>内部辅助方法，用于在 finally 块中安全关闭 JDBC 资源，
     * 避免关闭异常掩盖业务异常。</p>
     *
     * @param resource 待关闭的资源，允许为 null
     */
    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}
