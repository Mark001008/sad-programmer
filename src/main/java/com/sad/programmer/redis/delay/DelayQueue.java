package com.sad.programmer.redis.delay;

/**
 * 延迟队列接口（SDD 规格）。
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>基于 Redis ZSET 实现，score 为执行时间戳</li>
 *   <li>线程安全</li>
 *   <li>消息至少投递一次，不保证精确一次</li>
 * </ul>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>offer(message, delayMs) — 投递延迟消息</li>
 *   <li>poll() — 拉取已到期的消息，无到期消息返回 null</li>
 *   <li>size() — 返回队列中待处理消息数</li>
 * </ul>
 *
 * @author sad-programmer
 */
public interface DelayQueue {

    /**
     * 投递延迟消息。
     *
     * @param message  消息内容，不允许 null
     * @param delayMs  延迟时间（毫秒），必须 > 0
     */
    void offer(String message, long delayMs);

    /**
     * 拉取已到期的消息。
     *
     * <p>原子性地弹出 score <= 当前时间戳的第一条消息。</p>
     *
     * @return 到期的消息，无到期消息返回 null
     */
    String poll();

    /**
     * 返回队列中待处理消息数。
     *
     * @return 消息数量
     */
    long size();
}
