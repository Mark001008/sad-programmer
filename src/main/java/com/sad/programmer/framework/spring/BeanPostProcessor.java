package com.sad.programmer.framework.spring;

/**
 * Bean 后处理器，允许在 Bean 初始化前后插入自定义逻辑。
 *
 * <p>等价于 Spring 中的 BeanPostProcessor，是 Spring 扩展机制的核心。
 * AOP 代理、@Autowired 注入等功能都是通过此接口实现的。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface BeanPostProcessor {

    /**
     * 在 Bean 初始化之前执行。
     *
     * @param bean     Bean 实例
     * @param beanName Bean 名称
     * @return 处理后的 Bean（可以是代理对象）
     * @throws Exception 处理异常
     */
    Object postProcessBeforeInitialization(Object bean, String beanName) throws Exception;

    /**
     * 在 Bean 初始化之后执行。
     *
     * @param bean     Bean 实例
     * @param beanName Bean 名称
     * @return 处理后的 Bean（可以是代理对象）
     * @throws Exception 处理异常
     */
    Object postProcessAfterInitialization(Object bean, String beanName) throws Exception;
}
