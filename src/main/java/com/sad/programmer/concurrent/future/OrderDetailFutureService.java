package com.sad.programmer.concurrent.future;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link CompletableFuture} 的订单详情聚合服务 Demo。
 *
 * <p>企业场景：订单详情页并行查询多个下游，使用 CompletableFuture 做异步编排，
 * 使用 Java 8 兼容超时工具控制整体耗时，并对单个下游异常做降级。</p>
 */
public class OrderDetailFutureService {

    /**
     * 执行业务查询任务的线程池。
     */
    private final Executor queryExecutor;

    /**
     * 负责超时控制的调度线程池。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 创建订单详情聚合服务。
     *
     * @param queryExecutor 查询线程池
     * @param scheduler 超时调度线程池
     */
    public OrderDetailFutureService(Executor queryExecutor, ScheduledExecutorService scheduler) {
        if (queryExecutor == null) {
            throw new IllegalArgumentException("queryExecutor must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        this.queryExecutor = queryExecutor;
        this.scheduler = scheduler;
    }

    /**
     * 异步聚合订单详情。
     *
     * @param query 下游查询任务集合
     * @param timeout 整体超时时间
     * @param unit 时间单位
     * @return 聚合结果 Future
     */
    public CompletableFuture<OrderDetailResult> query(OrderDetailQuery query, long timeout, TimeUnit unit) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }

        CompletableFuture<String> orderFuture = supply(query.getOrderTask());
        CompletableFuture<String> inventoryFuture = supply(query.getInventoryTask())
                .exceptionally(throwable -> "INVENTORY_FALLBACK");
        CompletableFuture<String> paymentFuture = supply(query.getPaymentTask())
                .exceptionally(throwable -> "PAYMENT_FALLBACK");

        CompletableFuture<OrderDetailResult> combined = orderFuture
                .thenCombine(inventoryFuture, PartialOrderDetail::new)
                .thenCombine(paymentFuture, (partial, paymentInfo) -> new OrderDetailResult(
                        partial.getOrderInfo(),
                        partial.getInventoryInfo(),
                        paymentInfo,
                        true));

        return FutureTimeouts.withTimeout(combined, timeout, unit, scheduler)
                .exceptionally(throwable -> {
                    // 整体超时时，返回降级结果。真实系统通常还会打点、记录慢下游和触发告警。
                    return new OrderDetailResult("ORDER_TIMEOUT", "INVENTORY_UNKNOWN", "PAYMENT_UNKNOWN", false);
                });
    }

    /**
     * 把 {@link Callable} 包装成 {@link CompletableFuture}。
     *
     * @param callable 查询任务
     * @return 查询结果 Future
     */
    private CompletableFuture<String> supply(final Callable<String> callable) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, queryExecutor);
    }

    /**
     * 订单和库存的中间聚合结果。
     */
    private static class PartialOrderDetail {

        /**
         * 订单基础信息。
         */
        private final String orderInfo;

        /**
         * 库存信息。
         */
        private final String inventoryInfo;

        /**
         * 创建中间聚合结果。
         *
         * @param orderInfo 订单基础信息
         * @param inventoryInfo 库存信息
         */
        private PartialOrderDetail(String orderInfo, String inventoryInfo) {
            this.orderInfo = orderInfo;
            this.inventoryInfo = inventoryInfo;
        }

        /**
         * 返回订单基础信息。
         *
         * @return 订单基础信息
         */
        private String getOrderInfo() {
            return orderInfo;
        }

        /**
         * 返回库存信息。
         *
         * @return 库存信息
         */
        private String getInventoryInfo() {
            return inventoryInfo;
        }
    }
}
