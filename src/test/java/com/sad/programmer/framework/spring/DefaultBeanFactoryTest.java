package com.sad.programmer.framework.spring;

import com.sad.programmer.framework.spring.aspect.LogAspect;
import com.sad.programmer.framework.spring.service.IOrderService;
import com.sad.programmer.framework.spring.service.OrderService;
import com.sad.programmer.framework.spring.service.UserRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * MiniSpring IoC 容器测试。
 *
 * <p>验证包扫描、依赖注入、生命周期回调、AOP 代理等核心功能。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class DefaultBeanFactoryTest {

    /** IoC 容器实例。 */
    private DefaultBeanFactory factory;

    /**
     * 测试前置：初始化容器，注册测试 Bean。
     */
    @Before
    public void setUp() throws Exception {
        factory = new DefaultBeanFactory();

        // 加载配置文件
        factory.loadProperties();

        // 注册 BeanDefinition
        factory.registerBeanDefinition(UserRepository.class);
        factory.registerBeanDefinition(OrderService.class);
        factory.registerBeanDefinition(LogAspect.class);

        // 注册切面类
        factory.registerAspectClass(LogAspect.class);

        // 添加 AOP 后处理器
        factory.addBeanPostProcessor(new AopBeanPostProcessor(factory));
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

    /**
     * 验证依赖注入：@MiniAutowired 注入 UserRepository。
     */
    @Test
    public void shouldInjectDependencyWhenAutowired() {
        // 获取 OrderService（通过接口获取，因为 AOP 代理返回的是代理对象）
        IOrderService orderService = (IOrderService) factory.getBean("orderService");
        assertNotNull("OrderService 应该被创建", orderService);
        assertTrue("OrderService 应该已初始化", orderService.isInitialized());
    }

    /**
     * 验证配置注入：@MiniValue 从 application.properties 注入值。
     */
    @Test
    public void shouldInjectValueFromProperties() {
        IOrderService orderService = (IOrderService) factory.getBean("orderService");
        // 调用 createOrder，验证 prefix 已注入（返回值应以 "ORDER-" 开头）
        String result = orderService.createOrder("001", 100L);
        assertTrue("返回值应包含 ORDER- 前缀", result.startsWith("ORDER-"));
    }

    /**
     * 验证单例作用域：多次获取返回同一实例。
     */
    @Test
    public void shouldReturnSameInstanceForSingleton() {
        Object bean1 = factory.getBean("userRepository");
        Object bean2 = factory.getBean("userRepository");
        assertSame("单例 Bean 应返回同一实例", bean1, bean2);
    }

    /**
     * 验证 AOP 代理：切面通知被执行。
     */
    @Test
    public void shouldInvokeAspectWhenMethodCalled() {
        // 获取 OrderService（此时应该是代理对象）
        IOrderService orderService = (IOrderService) factory.getBean("orderService");

        // 调用方法
        String result = orderService.createOrder("002", 200L);
        assertNotNull("方法应返回结果", result);

        // 验证切面被触发（从容器获取同一个实例）
        LogAspect logAspect = (LogAspect) factory.getBean("logAspect");
        assertTrue("前置通知应被执行", logAspect.getBeforeCount() > 0);
        assertTrue("后置通知应被执行", logAspect.getAfterCount() > 0);
    }

    /**
     * 验证容器关闭：单例 Bean 被正确销毁。
     */
    @Test
    public void shouldDestroyBeansWhenContainerClosed() {
        // 验证 Bean 存在
        assertTrue("容器应包含 userRepository", factory.containsBean("userRepository"));

        // 关闭容器
        factory.close();

        // 关闭后再获取应抛异常
        try {
            factory.getBean("userRepository");
            fail("关闭后获取 Bean 应抛异常");
        } catch (RuntimeException e) {
            assertTrue("异常信息应包含 Bean 名称", e.getMessage().contains("userRepository"));
        }
    }

    /**
     * 验证 containsBean 判断。
     */
    @Test
    public void shouldReturnTrueWhenBeanExists() {
        assertTrue("应包含 orderService", factory.containsBean("orderService"));
        assertTrue("应包含 userRepository", factory.containsBean("userRepository"));
        assertFalse("不应包含不存在的 Bean", factory.containsBean("notExist"));
    }

    /**
     * 验证 isSingleton 判断。
     */
    @Test
    public void shouldReturnTrueForSingletonBean() {
        assertTrue("默认 Bean 应为单例", factory.isSingleton("orderService"));
    }
}
