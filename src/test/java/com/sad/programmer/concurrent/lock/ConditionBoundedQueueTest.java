package com.sad.programmer.concurrent.lock;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConditionBoundedQueue} 的生产者消费者测试。
 *
 * <p>该测试验证队列满时生产者会进入 notFull 条件队列等待，消费者取走元素后生产者被唤醒。</p>
 */
public class ConditionBoundedQueueTest {

    /**
     * 验证队列满时生产者阻塞，直到消费者取出元素后再继续写入。
     *
     * @throws Exception 测试线程等待失败或任务执行失败
     */
    @Test
    public void shouldBlockProducerWhenQueueIsFullAndWakeItAfterTake() throws Exception {
        final ConditionBoundedQueue<Integer> queue = new ConditionBoundedQueue<>(1);
        // 先填满容量为 1 的队列，让后续生产者进入等待。
        queue.put(1);

        final CountDownLatch producerStarted = new CountDownLatch(1);
        final CountDownLatch producerFinished = new CountDownLatch(1);
        ThreadPoolExecutor executor = newExecutor();
        try {
            Future<?> producer = executor.submit(() -> {
                try {
                    producerStarted.countDown();
                    // 队列已满，这里会阻塞在 notFull.await()。
                    queue.put(2);
                    producerFinished.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(producerStarted.await(1, TimeUnit.SECONDS));
            // 生产者已经启动，但队列未腾出空间前，不应该完成 put。
            assertFalse(producerFinished.await(100, TimeUnit.MILLISECONDS));
            // 消费一个元素后，notFull.signal() 会唤醒生产者。
            assertEquals(Integer.valueOf(1), queue.take());
            assertTrue(producerFinished.await(1, TimeUnit.SECONDS));
            producer.get(1, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(2), queue.take());
            assertEquals(0, queue.size());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 创建测试线程池。
     *
     * <p>使用手动创建的线程池，显式配置有界队列、自定义线程名和拒绝策略。</p>
     *
     * @return 测试线程池
     */
    private ThreadPoolExecutor newExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, "bounded-queue-demo-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
