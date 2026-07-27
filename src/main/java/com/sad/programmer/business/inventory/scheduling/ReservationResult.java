package com.sad.programmer.business.inventory.scheduling;

/**
 * 库存预占结果模型。
 *
 * <p>封装预占操作的执行结果，包含是否成功、预占ID（成功时）和结果消息（失败时）。
 * 通过静态工厂方法 {@link #success(String)} 和 {@link #failure(String)} 构建实例，
 * 避免暴露构造细节。</p>
 *
 * <p>同时可作为 DAO 层查询结果的载体，携带 status、expireTimeMillis 等数据库字段，
 * 供调度层服务在状态判断和过期检查时使用。</p>
 */
public final class ReservationResult {

    /** 是否预占成功。 */
    private final boolean success;

    /** 预占ID，成功时非空，失败时为 null。 */
    private final String reservationId;

    /** 结果消息，失败时为错误原因，成功时为提示信息。 */
    private final String message;

    /** 订单ID，DAO 查询时填充。 */
    private final String orderId;

    /** 商品ID，DAO 查询时填充。 */
    private final long productId;

    /** 预占数量，DAO 查询时填充。 */
    private final int quantity;

    /**
     * 预占状态：0=RESERVED, 1=LOCKED, 2=UNLOCKED, 3=CONFIRMED。
     * DAO 查询时填充，未查询时为 -1。
     */
    private final int status;

    /** 支付流水ID，DAO 查询时填充，未设置时为 null。 */
    private final String paymentId;

    /**
     * 预占过期时间戳（毫秒）。
     * DAO 查询时从 MySQL DATETIME 转换而来，未查询时为 0。
     */
    private final long expireTimeMillis;

    /**
     * 私有构造方法，通过静态工厂创建实例。
     *
     * @param success       是否成功
     * @param reservationId 预占ID，失败时可为 null
     * @param message       结果消息
     */
    private ReservationResult(boolean success, String reservationId, String message) {
        this.success = success;
        this.reservationId = reservationId;
        this.message = message;
        this.orderId = null;
        this.productId = 0L;
        this.quantity = 0;
        this.status = -1;
        this.paymentId = null;
        this.expireTimeMillis = 0L;
    }

    /**
     * DAO 查询结果构造方法。
     *
     * <p>用于 {@link ReservationDao#findById} 返回数据库完整记录时构建实例。
     * success 设为 true（记录存在即视为有效），message 设为 "OK"。</p>
     *
     * @param reservationId   预占ID
     * @param orderId         订单ID
     * @param productId       商品ID
     * @param quantity        预占数量
     * @param status          当前状态（0~3）
     * @param paymentId       支付流水ID，可为 null
     * @param expireTimeMillis 过期时间戳（毫秒）
     */
    public ReservationResult(String reservationId, String orderId, long productId,
                              int quantity, int status, String paymentId, long expireTimeMillis) {
        this.success = true;
        this.reservationId = reservationId;
        this.message = "OK";
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.paymentId = paymentId;
        this.expireTimeMillis = expireTimeMillis;
    }

    /**
     * 创建预占成功的结果。
     *
     * @param reservationId 预占ID，不能为空
     * @return 成功的预占结果实例
     * @throws IllegalArgumentException 当 reservationId 为 null 或空字符串时
     */
    public static ReservationResult success(String reservationId) {
        if (reservationId == null || reservationId.trim().isEmpty()) {
            throw new IllegalArgumentException("reservationId must not be null or empty");
        }
        return new ReservationResult(true, reservationId, "reservation succeeded");
    }

    /**
     * 创建预占失败的结果。
     *
     * @param message 失败原因描述，不能为空
     * @return 失败的预占结果实例
     * @throws IllegalArgumentException 当 message 为 null 或空字符串时
     */
    public static ReservationResult failure(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("message must not be null or empty");
        }
        return new ReservationResult(false, null, message);
    }

    /**
     * 获取是否预占成功。
     *
     * @return true 表示成功，false 表示失败
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取预占ID。
     *
     * @return 预占ID，失败时返回 null
     */
    public String getReservationId() {
        return reservationId;
    }

    /**
     * 获取结果消息。
     *
     * @return 结果消息，成功时为提示，失败时为错误原因
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取订单ID。
     *
     * @return 订单ID，非 DAO 查询结果时返回 null
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * 获取商品ID。
     *
     * @return 商品ID，非 DAO 查询结果时返回 0
     */
    public long getProductId() {
        return productId;
    }

    /**
     * 获取预占数量。
     *
     * @return 预占数量，非 DAO 查询结果时返回 0
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * 获取预占状态。
     *
     * @return 状态值（0=RESERVED, 1=LOCKED, 2=UNLOCKED, 3=CONFIRMED），
     *         非 DAO 查询结果时返回 -1
     */
    public int getStatus() {
        return status;
    }

    /**
     * 获取支付流水ID。
     *
     * @return 支付流水ID，未设置时返回 null
     */
    public String getPaymentId() {
        return paymentId;
    }

    /**
     * 获取过期时间戳（毫秒）。
     *
     * @return 过期时间戳，非 DAO 查询结果时返回 0
     */
    public long getExpireTimeMillis() {
        return expireTimeMillis;
    }

    /**
     * 返回结果的字符串表示。
     *
     * @return 包含 success、reservationId、message 的可读字符串
     */
    @Override
    public String toString() {
        return "ReservationResult{"
                + "success=" + success
                + ", reservationId='" + reservationId + '\''
                + ", message='" + message + '\''
                + '}';
    }
}
