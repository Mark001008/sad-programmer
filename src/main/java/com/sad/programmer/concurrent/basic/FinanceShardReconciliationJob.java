package com.sad.programmer.concurrent.basic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用 {@link CountDownLatch} 编排财务分片对账任务的 Demo。
 *
 * <p>企业场景：财务日终任务通常会按商户、账期、账户段进行分片处理。
 * 主线程提交所有分片后，等待分片全部结束，再统一汇总成功失败数量。</p>
 */
public class FinanceShardReconciliationJob {

    /**
     * 执行分片任务的线程池。
     */
    private final ExecutorService executorService;

    /**
     * 创建财务分片对账任务。
     *
     * @param executorService 分片任务线程池
     */
    public FinanceShardReconciliationJob(ExecutorService executorService) {
        if (executorService == null) {
            throw new IllegalArgumentException("executorService must not be null");
        }
        this.executorService = executorService;
    }

    /**
     * 执行分片对账。
     *
     * @param shardCount 分片数量
     * @param processor 分片处理器
     * @param timeout 最大等待时间
     * @param unit 时间单位
     * @return 分片对账结果
     * @throws InterruptedException 等待过程中被中断
     */
    public FinanceShardReconciliationResult reconcile(int shardCount,
                                                      ShardProcessor processor,
                                                      long timeout,
                                                      TimeUnit unit) throws InterruptedException {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }

        final CountDownLatch doneLatch = new CountDownLatch(shardCount);
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();

        for (int shardNo = 0; shardNo < shardCount; shardNo++) {
            final int currentShardNo = shardNo;
            executorService.submit(() -> {
                try {
                    processor.process(currentShardNo);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    // 分片失败也必须 countDown，否则日终主流程会一直等不到结束信号。
                    doneLatch.countDown();
                }
            });
        }

        // 等待所有分片结束。超时后返回当前统计结果，生产中可触发告警或补偿任务。
        boolean completed = doneLatch.await(timeout, unit);
        return new FinanceShardReconciliationResult(
                completed,
                successCount.get(),
                failureCount.get());
    }

    /**
     * 分片处理器。
     */
    public interface ShardProcessor {

        /**
         * 处理指定分片。
         *
         * @param shardNo 分片编号
         * @throws Exception 分片处理异常
         */
        void process(int shardNo) throws Exception;
    }
}
