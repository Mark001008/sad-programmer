package com.sad.programmer.concurrent.future;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link OrderDetailFutureService} 面试实操测试。
 */
public class OrderDetailFutureServiceTest {

    /**
     * 执行订单、库存、支付查询的业务线程池。
     */
    private ThreadPoolExecutor queryExecutor;

    /**
     * 执行 Java 8 兼容超时任务的调度线程池。
     */
    private ScheduledThreadPoolExecutor scheduler;

    /**
     * 被测试的订单详情聚合服务。
     */
    private OrderDetailFutureService service;

    /**
     * 初始化测试依赖。
     */
    @Before
    public void setUp() {
        queryExecutor = new ThreadPoolExecutor(
                3,
                3,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(16),
                new NamedThreadFactory("order-detail-query"),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("order-detail-timeout"));
        scheduler.setRemoveOnCancelPolicy(true);
        service = new OrderDetailFutureService(queryExecutor, scheduler);
    }

    /**
     * 关闭测试线程池。
     */
    @After
    public void tearDown() {
        queryExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    /**
     * 验证 CompletableFuture 可以并行聚合订单、库存、支付三个下游结果。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldAggregateOrderDetailWithCompletableFuture() throws Exception {
        OrderDetailQuery query = new OrderDetailQuery(
                constantTask("ORDER_OK"),
                constantTask("STOCK_OK"),
                constantTask("PAID"));

        OrderDetailResult result = service.query(query, 1, TimeUnit.SECONDS).get(1, TimeUnit.SECONDS);

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("ORDER_OK", result.getOrderInfo());
        Assert.assertEquals("STOCK_OK", result.getInventoryInfo());
        Assert.assertEquals("PAID", result.getPaymentInfo());
    }

    /**
     * 验证单个非核心下游异常时，可以通过 exceptionally 返回降级值。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldFallbackSingleDownstreamException() throws Exception {
        OrderDetailQuery query = new OrderDetailQuery(
                constantTask("ORDER_OK"),
                failedTask(),
                constantTask("PAID"));

        OrderDetailResult result = service.query(query, 1, TimeUnit.SECONDS).get(1, TimeUnit.SECONDS);

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("ORDER_OK", result.getOrderInfo());
        Assert.assertEquals("INVENTORY_FALLBACK", result.getInventoryInfo());
        Assert.assertEquals("PAID", result.getPaymentInfo());
    }

    /**
     * 验证 Java 8 兼容超时工具可以让聚合 Future 先返回整体降级结果。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldReturnTimeoutFallbackInJava8CompatibleWay() throws Exception {
        final CountDownLatch releaseSlowTask = new CountDownLatch(1);
        OrderDetailQuery query = new OrderDetailQuery(
                blockingTask(releaseSlowTask),
                constantTask("STOCK_OK"),
                constantTask("PAID"));

        try {
            OrderDetailResult result = service.query(query, 100, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS);

            Assert.assertFalse(result.isCompleted());
            Assert.assertEquals("ORDER_TIMEOUT", result.getOrderInfo());
            Assert.assertEquals("INVENTORY_UNKNOWN", result.getInventoryInfo());
            Assert.assertEquals("PAYMENT_UNKNOWN", result.getPaymentInfo());
        } finally {
            releaseSlowTask.countDown();
        }
    }

    /**
     * 创建返回固定值的查询任务。
     *
     * @param value 固定返回值
     * @return 查询任务
     */
    private static Callable<String> constantTask(final String value) {
        return new Callable<String>() {
            public String call() {
                return value;
            }
        };
    }

    /**
     * 创建固定失败的查询任务。
     *
     * @return 查询任务
     */
    private static Callable<String> failedTask() {
        return new Callable<String>() {
            public String call() {
                throw new IllegalStateException("inventory service unavailable");
            }
        };
    }

    /**
     * 创建受 CountDownLatch 控制的慢查询任务。
     *
     * @param releaseSlowTask 释放慢任务的门闩
     * @return 查询任务
     */
    private static Callable<String> blockingTask(final CountDownLatch releaseSlowTask) {
        return new Callable<String>() {
            public String call() throws Exception {
                releaseSlowTask.await();
                return "ORDER_LATE";
            }
        };
    }

    /**
     * 测试用线程工厂，生成可识别的业务线程名。
     */
    private static class NamedThreadFactory implements ThreadFactory {

        /**
         * 线程名前缀。
         */
        private final String namePrefix;

        /**
         * 线程编号。
         */
        private final AtomicInteger sequence = new AtomicInteger(1);

        /**
         * 创建线程工厂。
         *
         * @param namePrefix 线程名前缀
         */
        private NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        /**
         * 创建新线程。
         *
         * @param runnable 待执行任务
         * @return 新线程
         */
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, namePrefix + "-" + sequence.getAndIncrement());
        }
    }
}
