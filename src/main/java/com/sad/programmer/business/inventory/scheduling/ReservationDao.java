package com.sad.programmer.business.inventory.scheduling;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 库存预占记录数据访问接口（DAO）。
 *
 * <p>定义对 reservation_record 表的 CRUD 操作，所有方法均接收外部传入的
 * {@link Connection}，由调用方控制事务边界和连接生命周期。</p>
 *
 * <p>状态约定：
 * <ul>
 *   <li>0 = RESERVED（预占中）</li>
 *   <li>1 = LOCKED（已锁定）</li>
 *   <li>2 = UNLOCKED（已解锁）</li>
 *   <li>3 = CONFIRMED（已确认扣减）</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public interface ReservationDao {

    /**
     * 根据预占ID查询预占记录。
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @return 预占结果，未找到时返回 null
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 reservationId 为 null 或空字符串时
     */
    ReservationResult findById(Connection conn, String reservationId) throws SQLException;

    /**
     * 插入一条预占记录。
     *
     * @param conn            数据库连接，由调用方管理事务
     * @param reservationId   预占ID，不能为空
     * @param orderId         订单ID，不能为空
     * @param productId       商品ID，必须大于 0
     * @param quantity        预占数量，必须大于 0
     * @param status          初始状态值（0~3）
     * @param expireTimeMillis 过期时间戳（毫秒），将转换为 MySQL DATETIME 存储
     * @return 受影响行数，成功插入时返回 1
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当必填参数不合法时
     */
    int insert(Connection conn, String reservationId, String orderId, long productId,
               int quantity, int status, long expireTimeMillis) throws SQLException;

    /**
     * CAS 方式更新预占状态。
     *
     * <p>仅当当前状态等于 fromStatus 时才更新为 toStatus，利用数据库行锁保证并发安全。
     * 返回 0 表示状态不匹配或记录不存在。</p>
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @param fromStatus    期望的当前状态
     * @param toStatus      目标状态
     * @return 受影响行数，成功更新时返回 1，状态不匹配返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 reservationId 为 null 或空字符串时
     */
    int updateStatus(Connection conn, String reservationId, int fromStatus, int toStatus) throws SQLException;

    /**
     * CAS 方式更新预占状态并记录支付流水ID。
     *
     * <p>仅当当前状态等于 fromStatus 时才更新为 toStatus 并写入 paymentId。
     * 用于锁定场景：RESERVED → LOCKED 时同时记录支付流水。</p>
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param reservationId 预占ID，不能为空
     * @param fromStatus    期望的当前状态
     * @param toStatus      目标状态
     * @param paymentId     支付流水ID，不能为空
     * @return 受影响行数，成功更新时返回 1，状态不匹配返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当必填参数不合法时
     */
    int updateStatusAndPaymentId(Connection conn, String reservationId, int fromStatus,
                                  int toStatus, String paymentId) throws SQLException;

    /**
     * 根据订单ID和商品ID查找未解锁的预占ID。
     *
     * <p>用于幂等检查：同一订单对同一商品只能有一条非 UNLOCKED 状态的预占记录。
     * 查询条件为 order_id = ? AND product_id = ? AND status != 2（UNLOCKED）。</p>
     *
     * @param conn      数据库连接，由调用方管理事务
     * @param orderId   订单ID，不能为空
     * @param productId 商品ID，必须大于 0
     * @return 匹配的预占ID，未找到时返回 null
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 orderId 为 null 或空字符串时
     */
    String findReservationIdByOrderProduct(Connection conn, String orderId, long productId) throws SQLException;
}
