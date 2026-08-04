package com.sad.programmer.framework.spring.service;

/**
 * 订单服务接口。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface IOrderService {

    /**
     * 创建订单。
     *
     * @param orderId 订单 ID
     * @param userId  用户 ID
     * @return 订单描述
     */
    String createOrder(String orderId, Long userId);

    /**
     * 查询是否已初始化。
     *
     * @return true 表示已初始化
     */
    boolean isInitialized();
}
