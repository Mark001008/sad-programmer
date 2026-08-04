package com.sad.programmer.framework.spring.circular;

import com.sad.programmer.framework.spring.annotation.MiniAutowired;
import com.sad.programmer.framework.spring.annotation.MiniComponent;
import com.sad.programmer.framework.spring.annotation.MiniPostConstruct;

/**
 * 循环依赖测试服务 B，依赖 ServiceA，形成 A → B → A 的循环。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
public class ServiceB {

    /** 依赖注入 ServiceA。 */
    @MiniAutowired
    private ServiceA serviceA;

    /** 标记是否已初始化。 */
    private boolean initialized = false;

    /**
     * 初始化回调。
     */
    @MiniPostConstruct
    public void init() {
        initialized = true;
    }

    /**
     * 调用 ServiceA 的方法，验证循环依赖注入成功。
     *
     * @return ServiceA 的返回值
     */
    public String callA() {
        return serviceA.hello();
    }

    /**
     * 返回自身标识。
     *
     * @return 标识字符串
     */
    public String hello() {
        return "B";
    }

    /**
     * 查询是否已初始化。
     *
     * @return true 表示已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
}
