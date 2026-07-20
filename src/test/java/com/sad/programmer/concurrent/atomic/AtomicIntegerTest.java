package com.sad.programmer.concurrent.atomic;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * {@link AtomicInteger} 的面试型并发测试。
 *
 * <p>测试覆盖三个企业常见使用方式：并发计数、CAS 抢占任务、本地库存扣减。
 * 注意：AtomicInteger 只保证单 JVM 内的单变量原子更新，不能直接解决分布式一致性。</p>
 */
public class AtomicIntegerTest {

    /**
     * 并发线程数量。
     */
    private static final int THREAD_COUNT = 20;

    /**
     * 每个线程执行自增次数。
     */
    private static final int INCREMENT_PER_THREAD = 10000;

    /**
     * 验证 AtomicInteger 可以修复普通 int 自增的丢失更新问题。
     *
     * @throws Exception 测试线程等待失败
     */
    @Test
    public void shouldCountCorrectlyWhenConcurrentIncrement() throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ThreadPoolExecutor executor = newExecutor("atomic-counter-demo-");
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.execute(() -> {
                    try {
                        // 所有线程等待同一个开始信号，尽量制造真实并发竞争。
                        startLatch.await();
                        for (int j = 0; j < INCREMENT_PER_THREAD; j++) {
                            counter.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            assertEquals(THREAD_COUNT * INCREMENT_PER_THREAD, counter.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证 compareAndSet 可以实现单 JVM 内的任务抢占。
     *
     * @throws Exception 测试线程等待失败
     */
    @Test
    public void shouldAllowOnlyOneThreadToClaimTaskByCompareAndSet() throws Exception {
        final AtomicInteger taskStatus = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ThreadPoolExecutor executor = newExecutor("atomic-claim-demo-");
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        // 只有一个线程可以把状态从 0 改成 1，模拟抢到本地任务执行权。
                        if (taskStatus.compareAndSet(0, 1)) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            assertEquals(1, successCount.get());
            assertEquals(1, taskStatus.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证 CAS 循环可以在单 JVM 内完成库存扣减，并避免扣成负数。
     *
     * @throws Exception 测试线程等待失败
     */
    @Test
    public void shouldDecrementLocalInventoryWithoutOverselling() throws Exception {
        final AtomicInteger stock = new AtomicInteger(100);
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicInteger failedCount = new AtomicInteger();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ThreadPoolExecutor executor = newExecutor("atomic-stock-demo-");
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 10; j++) {
                            if (tryDecrease(stock)) {
                                successCount.incrementAndGet();
                            } else {
                                failedCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();

            assertEquals(0, stock.get());
            assertEquals(100, successCount.get());
            assertEquals(100, failedCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 使用 CAS 循环扣减本地库存。
     *
     * @param stock 当前库存
     *
     * @return true 表示扣减成功；false 表示库存不足
     */
    private boolean tryDecrease(AtomicInteger stock) {
        for (;;) {
            int current = stock.get();
            if (current < 1) {
                return false;
            }

            int next = current - 1;
            // CAS 成功说明读取 current 到写入 next 期间，没有其他线程改掉库存。
            if (stock.compareAndSet(current, next)) {
                return true;
            }
            // CAS 失败说明发生并发修改，重新读取最新库存并再次判断。
        }
    }

    /**
     * 创建测试线程池。
     *
     * @param prefix 线程名前缀
     *
     * @return 测试线程池
     */
    private ThreadPoolExecutor newExecutor(final String prefix) {
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, prefix + sequence.incrementAndGet());
        return new ThreadPoolExecutor(AtomicIntegerTest.THREAD_COUNT, AtomicIntegerTest.THREAD_COUNT,
                0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(AtomicIntegerTest.THREAD_COUNT),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
