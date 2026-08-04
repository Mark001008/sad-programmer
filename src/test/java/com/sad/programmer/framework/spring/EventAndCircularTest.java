package com.sad.programmer.framework.spring;

import com.sad.programmer.framework.spring.circular.ServiceA;
import com.sad.programmer.framework.spring.circular.ServiceB;
import com.sad.programmer.framework.spring.event.OrderCreatedEvent;
import com.sad.programmer.framework.spring.event.OrderEventListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 事件机制和循环依赖测试。
 *
 * <p>验证 MiniSpring 容器的两大高级特性：
 * <ul>
 *   <li>事件机制：ApplicationEvent + ApplicationListener + publishEvent</li>
 *   <li>循环依赖：通过三级缓存解决字段注入的循环依赖</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class EventAndCircularTest {

    /** IoC 容器实例。 */
    private DefaultBeanFactory factory;

    /**
     * 测试前置：初始化容器。
     */
    @Before
    public void setUp() {
        factory = new DefaultBeanFactory();
    }

    /**
     * 测试后置：关闭容器。
     */
    @After
    public void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    // ========== 事件机制测试 ==========

    /**
     * 验证事件发布后监听器被调用。
     */
    @Test
    public void shouldInvokeListenerWhenEventPublished() {
        // 注册监听器 Bean
        factory.registerBeanDefinition(OrderEventListener.class);
        // 创建所有单例（会自动注册事件监听器）
        factory.getBean("orderEventListener");

        // 发布事件
        OrderCreatedEvent event = new OrderCreatedEvent(this, "ORD-001", 100L);
        factory.publishEvent(event);

        // 验证监听器被调用
        OrderEventListener listener = (OrderEventListener) factory.getBean("orderEventListener");
        assertEquals("监听器应处理 1 个事件", 1, listener.getHandledCount());
        assertEquals("最后处理的订单 ID 应匹配", "ORD-001", listener.getLastOrderId());
    }

    /**
     * 验证多次发布事件，监听器累计计数。
     */
    @Test
    public void shouldAccumulateEventCountWhenMultipleEventsPublished() {
        factory.registerBeanDefinition(OrderEventListener.class);
        factory.getBean("orderEventListener");

        factory.publishEvent(new OrderCreatedEvent(this, "ORD-001", 100L));
        factory.publishEvent(new OrderCreatedEvent(this, "ORD-002", 200L));
        factory.publishEvent(new OrderCreatedEvent(this, "ORD-003", 300L));

        OrderEventListener listener = (OrderEventListener) factory.getBean("orderEventListener");
        assertEquals("监听器应处理 3 个事件", 3, listener.getHandledCount());
        assertEquals("最后处理的订单 ID 应为 ORD-003", "ORD-003", listener.getLastOrderId());
    }

    /**
     * 验证手动注册的监听器也能正常工作。
     */
    @Test
    public void shouldInvokeManuallyRegisteredListener() {
        // 不通过 Bean 注册，直接手动注册监听器
        OrderEventListener listener = new OrderEventListener();
        factory.addEventListener(OrderCreatedEvent.class, listener);

        factory.publishEvent(new OrderCreatedEvent(this, "ORD-MANUAL", 500L));

        assertEquals("手动注册的监听器应被调用", 1, listener.getHandledCount());
        assertEquals("订单 ID 应匹配", "ORD-MANUAL", listener.getLastOrderId());
    }

    /**
     * 验证事件携带的时间戳有效。
     */
    @Test
    public void shouldHaveValidTimestampInEvent() {
        long before = System.currentTimeMillis();
        OrderCreatedEvent event = new OrderCreatedEvent(this, "ORD-TS", 100L);
        long after = System.currentTimeMillis();

        assertTrue("事件时间戳应在创建前后之间",
                event.getTimestamp() >= before && event.getTimestamp() <= after);
    }

    // ========== 循环依赖测试 ==========

    /**
     * 验证字段注入的循环依赖可以正常解决。
     *
     * <p>ServiceA 依赖 ServiceB，ServiceB 依赖 ServiceA，
     * 形成 A → B → A 的循环。通过三级缓存机制解决。</p>
     */
    @Test
    public void shouldResolveCircularDependencyBetweenServices() {
        factory.registerBeanDefinition(ServiceA.class);
        factory.registerBeanDefinition(ServiceB.class);

        // 创建所有单例
        ServiceA serviceA = (ServiceA) factory.getBean("serviceA");
        ServiceB serviceB = (ServiceB) factory.getBean("serviceB");

        // 验证两者都已创建和初始化
        assertTrue("ServiceA 应已初始化", serviceA.isInitialized());
        assertTrue("ServiceB 应已初始化", serviceB.isInitialized());

        // 验证循环依赖注入成功
        assertEquals("ServiceA 调用 ServiceB 应返回 B", "B", serviceA.callB());
        assertEquals("ServiceB 调用 ServiceA 应返回 A", "A", serviceB.callA());
    }

    /**
     * 验证循环依赖的两个 Bean 都是单例。
     */
    @Test
    public void shouldReturnSameInstanceForCircularDependentBeans() {
        factory.registerBeanDefinition(ServiceA.class);
        factory.registerBeanDefinition(ServiceB.class);

        ServiceA a1 = (ServiceA) factory.getBean("serviceA");
        ServiceA a2 = (ServiceA) factory.getBean("serviceA");
        ServiceB b1 = (ServiceB) factory.getBean("serviceB");
        ServiceB b2 = (ServiceB) factory.getBean("serviceB");

        assertSame("ServiceA 应为单例", a1, a2);
        assertSame("ServiceB 应为单例", b1, b2);
    }
}
