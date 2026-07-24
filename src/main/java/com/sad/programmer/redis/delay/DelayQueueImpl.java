package com.sad.programmer.redis.delay;

import com.sad.programmer.redis.common.RedisUtil;
import redis.clients.jedis.Jedis;

import java.util.Set;

/**
 * 延迟队列 Redis 实现。
 *
 * <h3>实现原理</h3>
 * <ol>
 *   <li>使用 ZSET 存储消息，score 为执行时间戳（毫秒）</li>
 *   <li>offer 时 ZADD 到集合</li>
 *   <li>poll 时用 Lua 脚本原子性 ZRANGEBYSCORE + ZREM 取出到期消息</li>
 * </ol>
 *
 * <h3>适用场景</h3>
 * <ul>
 *   <li>订单超时取消</li>
 *   <li>延迟通知</li>
 *   <li>定时任务调度</li>
 * </ul>
 *
 * @author sad-programmer
 */
public class DelayQueueImpl implements DelayQueue {

    /** Redis ZSET 键名，用于存储延迟消息 */
    private final String queueKey;

    /**
     * 创建延迟队列。
     *
     * @param queueKey Redis ZSET 键名，不允许为空
     * @throws IllegalArgumentException 当 queueKey 为 null 或空字符串时
     */
    public DelayQueueImpl(String queueKey) {
        if (queueKey == null || queueKey.isEmpty()) {
            throw new IllegalArgumentException("queueKey 不允许为空");
        }
        this.queueKey = queueKey;
    }

    /**
     * 投递延迟消息。
     *
     * <p>消息将在当前时间 + delayMs 后才可被消费。</p>
     *
     * @param message 消息内容，不允许 null
     * @param delayMs 延迟时间（毫秒），必须大于 0
     * @throws IllegalArgumentException 当 message 为 null 或 delayMs <= 0 时
     */
    @Override
    public void offer(String message, long delayMs) {
        if (message == null) {
            throw new IllegalArgumentException("message 不允许为 null");
        }
        if (delayMs <= 0) {
            throw new IllegalArgumentException("delayMs 必须 > 0，当前值: " + delayMs);
        }
        long executeAt = System.currentTimeMillis() + delayMs;
        Jedis jedis = RedisUtil.getResource();
        try {
            jedis.zadd(queueKey, executeAt, message);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 拉取已到期的消息。
     *
     * <p>使用 Lua 脚本原子性地弹出 score <= 当前时间戳的第一条消息，
     * 保证并发安全，不会重复消费。</p>
     *
     * @return 到期的消息内容，无到期消息返回 null
     */
    @Override
    public String poll() {
        long now = System.currentTimeMillis();
        Jedis jedis = RedisUtil.getResource();
        try {
            // Lua 脚本：原子性地取出并删除一条到期消息
            String lua =
                "local msgs = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 1) " +
                "if #msgs > 0 then " +
                "  redis.call('ZREM', KEYS[1], msgs[1]) " +
                "  return msgs[1] " +
                "end " +
                "return nil";
            Object result = jedis.eval(lua, 1, queueKey, String.valueOf(now));
            return result != null ? result.toString() : null;
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 返回队列中待处理消息总数。
     *
     * @return 消息数量（包括未到期和已到期未消费的）
     */
    @Override
    public long size() {
        Jedis jedis = RedisUtil.getResource();
        try {
            return jedis.zcard(queueKey);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }

    /**
     * 查看队列中所有待处理消息（调试用）。
     *
     * @return 消息集合，按 score（执行时间）升序排列
     */
    public Set<String> peekAll() {
        Jedis jedis = RedisUtil.getResource();
        try {
            return jedis.zrange(queueKey, 0, -1);
        } finally {
            RedisUtil.returnResource(jedis);
        }
    }
}
