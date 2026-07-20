package com.sad.programmer.concurrent.future;

/**
 * 订单详情聚合查询结果。
 */
public class OrderDetailResult {

    /**
     * 订单基础信息。
     */
    private final String orderInfo;

    /**
     * 库存信息。
     */
    private final String inventoryInfo;

    /**
     * 支付信息。
     */
    private final String paymentInfo;

    /**
     * 是否在超时时间内完整聚合。
     */
    private final boolean completed;

    /**
     * 创建订单详情结果。
     *
     * @param orderInfo 订单基础信息
     * @param inventoryInfo 库存信息
     * @param paymentInfo 支付信息
     * @param completed 是否完整聚合
     */
    public OrderDetailResult(String orderInfo, String inventoryInfo, String paymentInfo, boolean completed) {
        this.orderInfo = orderInfo;
        this.inventoryInfo = inventoryInfo;
        this.paymentInfo = paymentInfo;
        this.completed = completed;
    }

    /**
     * 返回订单基础信息。
     *
     * @return 订单基础信息
     */
    public String getOrderInfo() {
        return orderInfo;
    }

    /**
     * 返回库存信息。
     *
     * @return 库存信息
     */
    public String getInventoryInfo() {
        return inventoryInfo;
    }

    /**
     * 返回支付信息。
     *
     * @return 支付信息
     */
    public String getPaymentInfo() {
        return paymentInfo;
    }

    /**
     * 返回是否完整聚合。
     *
     * @return true 表示全部下游在超时时间内完成
     */
    public boolean isCompleted() {
        return completed;
    }
}
