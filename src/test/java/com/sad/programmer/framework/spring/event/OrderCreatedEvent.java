package com.sad.programmer.framework.spring.event;

import com.sad.programmer.framework.spring.ApplicationEvent;

/**
 * 订单创建事件，用于测试事件机制。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class OrderCreatedEvent extends ApplicationEvent {

    /** 订单 ID。 */
    private final String orderId;

    /** 订单金额。 */
    private final long amount;

    /**
     * 构造订单创建事件。
     *
     * @param source  事件源
     * @param orderId 订单 ID
     * @param amount  订单金额
     */
    public OrderCreatedEvent(Object source, String orderId, long amount) {
        super(source);
        this.orderId = orderId;
        this.amount = amount;
    }

    /**
     * 获取订单 ID。
     *
     * @return 订单 ID
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * 获取订单金额。
     *
     * @return 订单金额
     */
    public long getAmount() {
        return amount;
    }
}
