package com.sad.programmer.concurrent.future;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link FutureTask} 面试考点测试。
 */
public class FutureTaskTest {

    /**
     * 执行 FutureTask 的测试线程池。
     */
    private ThreadPoolExecutor executor;

    /**
     * 初始化有界队列线程池。
     */
    @Before
    public void setUp() {
        executor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8),
                new NamedThreadFactory("future-task-test"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 关闭测试线程池。
     */
    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    /**
     * 验证 FutureTask 正常完成后，get 可以拿到 Callable 返回值。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldReturnResultWhenFutureTaskCompletes() throws Exception {
        FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
            public String call() {
                return "OK";
            }
        });

        executor.execute(task);

        Assert.assertEquals("OK", task.get(1, TimeUnit.SECONDS));
        Assert.assertTrue(task.isDone());
        Assert.assertFalse(task.isCancelled());
    }

    /**
     * 验证任务没有完成时，带超时时间的 get 会抛出 TimeoutException。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldTimeoutWhenFutureTaskNotDone() throws Exception {
        final CountDownLatch releaseTask = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
            public String call() throws Exception {
                releaseTask.await();
                return "DONE";
            }
        });

        executor.execute(task);

        try {
            task.get(100, TimeUnit.MILLISECONDS);
            Assert.fail("FutureTask should timeout before latch is released");
        } catch (TimeoutException expected) {
            Assert.assertFalse(task.isDone());
        } finally {
            releaseTask.countDown();
        }

        Assert.assertEquals("DONE", task.get(1, TimeUnit.SECONDS));
    }

    /**
     * 验证 cancel(true) 会尝试中断正在执行任务的线程。
     *
     * @throws Exception 测试异常
     */
    @Test
    public void shouldCancelFutureTaskAndInterruptRunner() throws Exception {
        final CountDownLatch runnerStarted = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch keepRunning = new CountDownLatch(1);
        final AtomicBoolean interruptedFlag = new AtomicBoolean(false);

        FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
            public String call() throws Exception {
                runnerStarted.countDown();
                try {
                    keepRunning.await();
                    return "SHOULD_NOT_RETURN";
                } catch (InterruptedException e) {
                    // cancel(true) 只负责发出中断信号，任务要正确响应中断才会尽快退出。
                    interruptedFlag.set(true);
                    interrupted.countDown();
                    throw e;
                }
            }
        });

        executor.execute(task);
        Assert.assertTrue(runnerStarted.await(1, TimeUnit.SECONDS));

        Assert.assertTrue(task.cancel(true));
        Assert.assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(interruptedFlag.get());
        Assert.assertTrue(task.isDone());
        Assert.assertTrue(task.isCancelled());
    }

    /**
     * 测试用线程工厂，便于从线程名判断任务归属。
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
