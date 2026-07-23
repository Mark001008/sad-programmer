package com.sad.programmer.concurrent.basic;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link OrderQueryAggregationService} 的订单聚合查询测试。
 */
public class OrderQueryAggregationServiceTest {

    /**
     * 验证多个下游任务都完成时，聚合结果标记为 completed。
     *
     * @throws Exception 测试执行异常
     */
    @Test
    public void shouldAggregateAllDownstreamResults() throws Exception {
        ThreadPoolExecutor executor = newExecutor("order-aggregation-demo-", 3);
        try {
            OrderQueryAggregationService service = new OrderQueryAggregationService(executor);

            OrderQueryAggregationResult result = service.aggregate(Arrays.asList(
                    task("order", "ORDER_OK"),
                    task("inventory", "STOCK_OK"),
                    task("payment", "PAID")
            ), 1, TimeUnit.SECONDS);

            assertTrue(result.isCompleted());
            assertEquals("ORDER_OK", result.getValue("order"));
            assertEquals("STOCK_OK", result.getValue("inventory"));
            assertEquals("PAID", result.getValue("payment"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证某个下游迟迟不返回时，主线程可以超时返回已经完成的部分结果。
     *
     * @throws Exception 测试执行异常
     */
    @Test
    public void shouldReturnPartialResultsWhenTimeout() throws Exception {
        ThreadPoolExecutor executor = newExecutor("order-aggregation-timeout-demo-", 2);
        final CountDownLatch releaseSlowTask = new CountDownLatch(1);
        try {
            OrderQueryAggregationService service = new OrderQueryAggregationService(executor);
            List<OrderQueryAggregationService.NamedQueryTask> tasks = Arrays.asList(
                    task("order", "ORDER_OK"),
                    new OrderQueryAggregationService.NamedQueryTask("payment", () -> {
                        // 阻塞慢下游，模拟支付系统查询超时。
                        releaseSlowTask.await();
                        return "PAID";
                    })
            );

            OrderQueryAggregationResult result = service.aggregate(tasks, 100, TimeUnit.MILLISECONDS);

            assertFalse(result.isCompleted());
            assertEquals("ORDER_OK", result.getValue("order"));
            assertEquals(1, result.getValues().size());
        } finally {
            releaseSlowTask.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 创建固定返回值的下游任务。
     *
     * @param name 下游名称
     * @param value 下游返回值
     * @return 下游查询任务
     */
    private OrderQueryAggregationService.NamedQueryTask task(final String name, final String value) {
        return new OrderQueryAggregationService.NamedQueryTask(name, () -> value);
    }

    /**
     * 创建测试线程池。
     *
     * @param prefix 线程名前缀
     * @param poolSize 线程数
     * @return 测试线程池
     */
    private ThreadPoolExecutor newExecutor(final String prefix, int poolSize) {
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, prefix + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(10),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
