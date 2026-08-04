package com.sad.programmer.framework.spring.service;

import com.sad.programmer.framework.spring.annotation.MiniAutowired;
import com.sad.programmer.framework.spring.annotation.MiniComponent;
import com.sad.programmer.framework.spring.annotation.MiniPostConstruct;
import com.sad.programmer.framework.spring.annotation.MiniValue;

/**
 * 订单服务，演示依赖注入和生命周期。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
public class OrderService implements IOrderService {

    /** 用户名前缀，从配置文件注入。 */
    @MiniValue("order.prefix")
    private String prefix;

    /** 依赖注入用户仓储。 */
    @MiniAutowired
    private UserRepository userRepository;

    /** 标记是否已初始化。 */
    private boolean initialized = false;

    /**
     * 初始化回调，验证依赖注入是否成功。
     */
    @MiniPostConstruct
    public void init() {
        if (prefix == null) {
            throw new IllegalStateException("prefix 未注入");
        }
        if (userRepository == null) {
            throw new IllegalStateException("userRepository 未注入");
        }
        initialized = true;
    }

    @Override
    public String createOrder(String orderId, Long userId) {
        String userName = userRepository.findUserName(userId);
        return prefix + orderId + " -> " + userName;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}
