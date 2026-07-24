package com.sad.programmer.redis.delay;

import com.sad.programmer.redis.common.RedisUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * DelayQueueImpl 测试。
 *
 * <p>覆盖：正常投递/消费、延迟效果、边界条件、异常路径。</p>
 *
 * @author sad-programmer
 */
public class DelayQueueImplTest {

    /** 延迟队列实例 */
    private DelayQueueImpl queue;

    /** Redis ZSET 键名，使用 UUID 保证测试隔离 */
    private String testKey;

    /**
     * 初始化测试环境，创建延迟队列实例。
     */
    @Before
    public void setUp() {
        testKey = "delay:test:" + UUID.randomUUID().toString().substring(0, 8);
        queue = new DelayQueueImpl(testKey);
    }

    /**
     * 清理 Redis 中的测试数据。
     */
    @After
    public void tearDown() {
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.del(testKey);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    // ==================== 正常路径 ====================

    /**
     * 验证消息在延迟到期后可以被 poll 取出。
     */
    @Test
    public void shouldOfferAndPollImmediately() throws InterruptedException {
        queue.offer("msg1", 100);
        assertEquals(1, queue.size());
        Thread.sleep(150);
        assertEquals("msg1", queue.poll());
        assertEquals(0, queue.size());
    }

    /**
     * 验证多条消息按到期时间顺序消费。
     */
    @Test
    public void shouldPollMultipleMessagesInOrder() throws InterruptedException {
        queue.offer("first", 100);
        queue.offer("second", 200);
        queue.offer("third", 300);

        Thread.sleep(350);

        assertEquals("first", queue.poll());
        assertEquals("second", queue.poll());
        assertEquals("third", queue.poll());
        assertNull(queue.poll());
    }

    /**
     * 验证没有到期消息时 poll 返回 null。
     */
    @Test
    public void shouldReturnNullWhenNoExpiredMessages() {
        queue.offer("future", 10000);
        assertNull(queue.poll());
    }

    // ==================== 延迟效果 ====================

    /**
     * 验证延迟未到期时 poll 返回 null。
     */
    @Test
    public void shouldNotPollBeforeDelay() throws InterruptedException {
        queue.offer("delayed", 5000);
        assertNull(queue.poll());
        Thread.sleep(5100);
        assertEquals("delayed", queue.poll());
    }

    /**
     * 验证不同延迟时间的消息按预期顺序到期。
     */
    @Test
    public void shouldHandleDifferentDelays() throws InterruptedException {
        queue.offer("fast", 2000);
        queue.offer("medium", 5000);
        queue.offer("slow", 10000);

        Thread.sleep(2100);
        assertEquals("fast", queue.poll());
        assertNull(queue.poll());

        Thread.sleep(3100);
        assertEquals("medium", queue.poll());
        assertNull(queue.poll());

        Thread.sleep(5100);
        assertEquals("slow", queue.poll());
    }

    // ==================== 边界条件 ====================

    /**
     * 验证 size 方法正确返回队列中消息数量。
     */
    @Test
    public void shouldReturnCorrectSize() {
        assertEquals(0, queue.size());
        queue.offer("a", 1000);
        assertEquals(1, queue.size());
        queue.offer("b", 2000);
        assertEquals(2, queue.size());
    }

    /**
     * 验证 peekAll 返回所有待处理消息。
     */
    @Test
    public void shouldPeekAllMessages() {
        queue.offer("x", 1000);
        queue.offer("y", 2000);
        assertEquals(2, queue.peekAll().size());
    }

    /**
     * 验证 message 为 null 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenMessageIsNull() {
        queue.offer(null, 1000);
    }

    /**
     * 验证 delayMs 为 0 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenDelayIsZero() {
        queue.offer("msg", 0);
    }

    /**
     * 验证 delayMs 为负数时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenDelayIsNegative() {
        queue.offer("msg", -100);
    }

    /**
     * 验证 queueKey 为空字符串时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenQueueKeyIsEmpty() {
        new DelayQueueImpl("");
    }

    /**
     * 验证 queueKey 为 null 时抛出 IllegalArgumentException。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenQueueKeyIsNull() {
        new DelayQueueImpl(null);
    }
}
