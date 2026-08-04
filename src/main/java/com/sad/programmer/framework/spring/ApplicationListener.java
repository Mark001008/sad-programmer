package com.sad.programmer.framework.spring;

import java.util.EventListener;

/**
 * 应用事件监听器接口，所有事件消费者应实现此接口。
 *
 * <p>基于 JDK 的 EventListener，通过泛型参数指定监听的事件类型。
 * 容器在发布事件时会遍历所有匹配类型的监听器并调用 onApplicationEvent。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * public class OrderCreatedListener implements ApplicationListener<OrderCreatedEvent> {
 *     @Override
 *     public void onApplicationEvent(OrderCreatedEvent event) {
 *         // 处理订单创建事件
 *     }
 * }
 * }</pre></p>
 *
 * @param <E> 监听的事件类型
 * @author sad-programmer
 * @since 1.0.0
 */
public interface ApplicationListener<E extends ApplicationEvent> extends EventListener {

    /**
     * 处理应用事件。
     *
     * @param event 应用事件
     */
    void onApplicationEvent(E event);
}
