package com.sad.programmer.concurrent.basic;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link FinanceShardReconciliationJob} 的财务分片对账测试。
 */
public class FinanceShardReconciliationJobTest {

    /**
     * 验证所有分片结束后，主流程可以汇总成功和失败数量。
     *
     * @throws Exception 测试执行异常
     */
    @Test
    public void shouldWaitAllShardsAndCollectSummary() throws Exception {
        ThreadPoolExecutor executor = newExecutor("finance-reconcile-demo-", 4);
        try {
            FinanceShardReconciliationJob job = new FinanceShardReconciliationJob(executor);

            FinanceShardReconciliationResult result = job.reconcile(
                    4,
                    new FinanceShardReconciliationJob.ShardProcessor() {
                        public void process(int shardNo) {
                            if (shardNo == 2) {
                                throw new IllegalStateException("mock shard failure");
                            }
                        }
                    },
                    1,
                    TimeUnit.SECONDS);

            assertTrue(result.isCompleted());
            assertEquals(3, result.getSuccessCount());
            assertEquals(1, result.getFailureCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证某个分片卡住时，主流程可以超时返回当前汇总结果。
     *
     * @throws Exception 测试执行异常
     */
    @Test
    public void shouldReturnCurrentSummaryWhenShardTimeout() throws Exception {
        ThreadPoolExecutor executor = newExecutor("finance-reconcile-timeout-demo-", 2);
        final CountDownLatch releaseSlowShard = new CountDownLatch(1);
        try {
            FinanceShardReconciliationJob job = new FinanceShardReconciliationJob(executor);

            FinanceShardReconciliationResult result = job.reconcile(
                    2,
                    new FinanceShardReconciliationJob.ShardProcessor() {
                        public void process(int shardNo) throws Exception {
                            if (shardNo == 1) {
                                // 阻塞一个分片，模拟下游数据库或账务文件处理长时间无响应。
                                releaseSlowShard.await();
                            }
                        }
                    },
                    100,
                    TimeUnit.MILLISECONDS);

            assertFalse(result.isCompleted());
            assertEquals(1, result.getSuccessCount());
            assertEquals(0, result.getFailureCount());
        } finally {
            releaseSlowShard.countDown();
            executor.shutdownNow();
        }
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
        ThreadFactory threadFactory = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                return new Thread(r, prefix + sequence.incrementAndGet());
            }
        };
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
