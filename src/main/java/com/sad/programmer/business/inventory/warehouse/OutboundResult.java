package com.sad.programmer.business.inventory.warehouse;

/**
 * 出库操作结果类。
 *
 * <p>封装出库操作的执行结果，包括是否成功、出库单号和结果消息。
 * 所有字段均为 final，实例创建后不可变。通过静态工厂方法构造实例。</p>
 */
public final class OutboundResult {

    /**
     * 出库操作是否成功。
     */
    private final boolean success;

    /**
     * 出库单号，仅在成功时有值。
     */
    private final String outboundId;

    /**
     * 结果消息，成功或失败的描述信息。
     */
    private final String message;

    /**
     * 构造出库结果。
     *
     * @param success    是否成功
     * @param outboundId 出库单号，失败时可为 null
     * @param message    结果消息
     */
    private OutboundResult(final boolean success, final String outboundId, final String message) {
        this.success = success;
        this.outboundId = outboundId;
        this.message = message;
    }

    /**
     * 创建成功的出库结果。
     *
     * @param outboundId 出库单号，不能为空
     * @return 成功的出库结果实例
     * @throws IllegalArgumentException 当 outboundId 为 null 或空白时抛出
     */
    public static OutboundResult success(final String outboundId) {
        if (outboundId == null || outboundId.trim().isEmpty()) {
            throw new IllegalArgumentException("出库单号不能为空");
        }
        return new OutboundResult(true, outboundId, "出库成功");
    }

    /**
     * 创建失败的出库结果。
     *
     * @param message 失败原因描述
     * @return 失败的出库结果实例
     * @throws IllegalArgumentException 当 message 为 null 或空白时抛出
     */
    public static OutboundResult failure(final String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("失败消息不能为空");
        }
        return new OutboundResult(false, null, message);
    }

    /**
     * 获取出库操作是否成功。
     *
     * @return true 表示成功，false 表示失败
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取出库单号。
     *
     * @return 出库单号，失败时返回 null
     */
    public String getOutboundId() {
        return outboundId;
    }

    /**
     * 获取结果消息。
     *
     * @return 结果消息
     */
    public String getMessage() {
        return message;
    }
}
