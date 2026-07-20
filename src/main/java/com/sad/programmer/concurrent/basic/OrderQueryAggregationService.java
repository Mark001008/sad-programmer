package com.sad.programmer.concurrent.basic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 使用 {@link CountDownLatch} 实现的订单聚合查询服务 Demo。
 *
 * <p>企业场景：订单详情页通常需要并行查询订单、库存、支付、物流等多个下游。
 * 主线程提交多个查询任务后，通过 CountDownLatch 等待所有任务完成或整体超时。</p>
 */
public class OrderQueryAggregationService {

    /**
     * 执行下游查询任务的线程池。
     *
     * <p>线程池由调用方传入，便于生产环境统一配置线程名称、有界队列、拒绝策略和监控。</p>
     */
    private final ExecutorService executorService;

    /**
     * 创建订单聚合查询服务。
     *
     * @param executorService 查询线程池
     */
    public OrderQueryAggregationService(ExecutorService executorService) {
        if (executorService == null) {
            throw new IllegalArgumentException("executorService must not be null");
        }
        this.executorService = executorService;
    }

    /**
     * 并行执行多个下游查询，并等待全部完成或超时。
     *
     * @param tasks 下游查询任务列表
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 聚合查询结果
     * @throws InterruptedException 等待过程中被中断
     */
    public OrderQueryAggregationResult aggregate(List<NamedQueryTask> tasks,
                                                 long timeout,
                                                 TimeUnit unit) throws InterruptedException {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }

        final CountDownLatch doneLatch = new CountDownLatch(tasks.size());
        final ConcurrentHashMap<String, String> resultMap = new ConcurrentHashMap<>();

        for (final NamedQueryTask task : tasks) {
            executorService.submit(() -> {
                try {
                    resultMap.put(task.getName(), task.call());
                } catch (Exception e) {
                    resultMap.put(task.getName(), "ERROR:" + e.getClass().getSimpleName());
                } finally {
                    // 每个下游任务无论成功还是失败，都必须 countDown，避免主线程永久等待。
                    doneLatch.countDown();
                }
            });
        }

        // 主线程最多等待 timeout。超时后返回已完成的部分结果，生产中可配合降级展示。
        boolean completed = doneLatch.await(timeout, unit);
        return new OrderQueryAggregationResult(completed, sortByTaskOrder(tasks, resultMap));
    }

    /**
     * 按任务提交顺序整理结果，方便测试和日志阅读。
     *
     * @param tasks 原始任务列表
     * @param resultMap 并发结果 Map
     * @return 按任务顺序排列后的结果
     */
    private Map<String, String> sortByTaskOrder(List<NamedQueryTask> tasks, Map<String, String> resultMap) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (NamedQueryTask task : tasks) {
            if (resultMap.containsKey(task.getName())) {
                ordered.put(task.getName(), resultMap.get(task.getName()));
            }
        }
        return ordered;
    }

    /**
     * 带名称的下游查询任务。
     *
     * <p>名称用于标识下游系统，例如 order、inventory、payment。</p>
     */
    public static class NamedQueryTask {

        /**
         * 下游名称。
         */
        private final String name;

        /**
         * 实际查询逻辑。
         */
        private final Callable<String> callable;

        /**
         * 创建下游查询任务。
         *
         * @param name 下游名称
         * @param callable 查询逻辑
         */
        public NamedQueryTask(String name, Callable<String> callable) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (callable == null) {
                throw new IllegalArgumentException("callable must not be null");
            }
            this.name = name;
            this.callable = callable;
        }

        /**
         * 返回下游名称。
         *
         * @return 下游名称
         */
        public String getName() {
            return name;
        }

        /**
         * 执行查询任务。
         *
         * @return 查询结果
         * @throws Exception 下游查询异常
         */
        public String call() throws Exception {
            return callable.call();
        }
    }
}
