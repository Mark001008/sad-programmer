package com.sad.programmer.concurrent.lock;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link LocalTaskSingleFlightGuard} 的单机任务防重入测试。
 *
 * <p>该测试模拟上一轮任务未结束时，下一轮调度进入，验证第二个任务会被跳过。</p>
 */
public class LocalTaskSingleFlightGuardTest {

    /**
     * 验证已有任务运行时，第二个任务不会阻塞等待，而是直接返回 false。
     *
     * @throws Exception 测试线程等待失败或任务执行失败
     */
    @Test
    public void shouldSkipSecondTaskWhenPreviousTaskIsStillRunning() throws Exception {
        final LocalTaskSingleFlightGuard guard = new LocalTaskSingleFlightGuard();
        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);

        ThreadPoolExecutor executor = newExecutor();
        try {
            Future<Boolean> first = executor.submit(() -> guard.tryRun(() -> {
                taskStarted.countDown();
                // 人为保持第一轮任务占用锁，模拟定时任务执行时间超过调度间隔。
                await(releaseTask);
            }));

            assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
            // 第一轮任务还没释放锁，第二轮任务应该被跳过，而不是阻塞调度线程。
            assertFalse(guard.tryRun(() -> {
                throw new AssertionError("second task should be skipped");
            }));

            // 释放第一轮任务后，后续任务应该可以继续执行。
            releaseTask.countDown();
            assertTrue(first.get(1, TimeUnit.SECONDS));
            assertTrue(guard.tryRun(() -> {
            }));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 等待门闩，测试辅助方法。
     *
     * @param latch 需要等待的门闩
     */
    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 创建测试线程池。
     *
     * <p>使用有界队列和自定义线程名，避免测试中引入 Executors 快捷工厂的隐藏参数。</p>
     *
     * @return 测试线程池
     */
    private ThreadPoolExecutor newExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, "single-flight-demo-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
