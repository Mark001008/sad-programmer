package com.sad.programmer.framework.spring.event;

import com.sad.programmer.framework.spring.ApplicationListener;
import com.sad.programmer.framework.spring.annotation.MiniComponent;

/**
 * 订单事件监听器，测试事件监听和泛型解析。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
public class OrderEventListener implements ApplicationListener<OrderCreatedEvent> {

    /** 已处理的事件计数。 */
    private int handledCount = 0;

    /** 最后处理的订单 ID。 */
    private String lastOrderId;

    /**
     * 处理订单创建事件。
     *
     * @param event 订单创建事件
     */
    @Override
    public void onApplicationEvent(OrderCreatedEvent event) {
        handledCount++;
        lastOrderId = event.getOrderId();
    }

    /**
     * 获取已处理事件计数。
     *
     * @return 计数
     */
    public int getHandledCount() {
        return handledCount;
    }

    /**
     * 获取最后处理的订单 ID。
     *
     * @return 订单 ID
     */
    public String getLastOrderId() {
        return lastOrderId;
    }
}
