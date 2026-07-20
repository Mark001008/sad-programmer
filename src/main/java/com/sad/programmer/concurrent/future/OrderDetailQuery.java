package com.sad.programmer.concurrent.future;

import java.util.concurrent.Callable;

/**
 * 订单详情聚合查询的下游任务集合。
 *
 * <p>真实订单详情页通常需要并行查询订单基础信息、库存状态和支付状态。
 * 这里用 {@link Callable} 模拟下游 RPC 或数据库查询。</p>
 */
public class OrderDetailQuery {

    /**
     * 订单基础信息查询任务。
     */
    private final Callable<String> orderTask;

    /**
     * 库存信息查询任务。
     */
    private final Callable<String> inventoryTask;

    /**
     * 支付信息查询任务。
     */
    private final Callable<String> paymentTask;

    /**
     * 创建订单详情查询任务集合。
     *
     * @param orderTask 订单查询任务
     * @param inventoryTask 库存查询任务
     * @param paymentTask 支付查询任务
     */
    public OrderDetailQuery(Callable<String> orderTask,
                            Callable<String> inventoryTask,
                            Callable<String> paymentTask) {
        if (orderTask == null || inventoryTask == null || paymentTask == null) {
            throw new IllegalArgumentException("query task must not be null");
        }
        this.orderTask = orderTask;
        this.inventoryTask = inventoryTask;
        this.paymentTask = paymentTask;
    }

    /**
     * 返回订单查询任务。
     *
     * @return 订单查询任务
     */
    public Callable<String> getOrderTask() {
        return orderTask;
    }

    /**
     * 返回库存查询任务。
     *
     * @return 库存查询任务
     */
    public Callable<String> getInventoryTask() {
        return inventoryTask;
    }

    /**
     * 返回支付查询任务。
     *
     * @return 支付查询任务
     */
    public Callable<String> getPaymentTask() {
        return paymentTask;
    }
}
