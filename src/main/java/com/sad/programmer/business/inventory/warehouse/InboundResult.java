package com.sad.programmer.business.inventory.warehouse;

/**
 * 入库操作结果类。
 *
 * <p>封装入库操作的执行结果，包括是否成功、入库单号和结果消息。
 * 所有字段均为 final，实例创建后不可变。通过静态工厂方法构造实例。</p>
 */
public final class InboundResult {

    /**
     * 入库操作是否成功。
     */
    private final boolean success;

    /**
     * 入库单号，仅在成功时有值。
     */
    private final String inboundId;

    /**
     * 结果消息，成功或失败的描述信息。
     */
    private final String message;

    /**
     * 构造入库结果。
     *
     * @param success   是否成功
     * @param inboundId 入库单号，失败时可为 null
     * @param message   结果消息
     */
    private InboundResult(final boolean success, final String inboundId, final String message) {
        this.success = success;
        this.inboundId = inboundId;
        this.message = message;
    }

    /**
     * 创建成功的入库结果。
     *
     * @param inboundId 入库单号，不能为空
     * @return 成功的入库结果实例
     * @throws IllegalArgumentException 当 inboundId 为 null 或空白时抛出
     */
    public static InboundResult success(final String inboundId) {
        if (inboundId == null || inboundId.trim().isEmpty()) {
            throw new IllegalArgumentException("入库单号不能为空");
        }
        return new InboundResult(true, inboundId, "入库成功");
    }

    /**
     * 创建失败的入库结果。
     *
     * @param message 失败原因描述
     * @return 失败的入库结果实例
     * @throws IllegalArgumentException 当 message 为 null 或空白时抛出
     */
    public static InboundResult failure(final String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("失败消息不能为空");
        }
        return new InboundResult(false, null, message);
    }

    /**
     * 获取入库操作是否成功。
     *
     * @return true 表示成功，false 表示失败
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取入库单号。
     *
     * @return 入库单号，失败时返回 null
     */
    public String getInboundId() {
        return inboundId;
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
