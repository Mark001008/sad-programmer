package com.sad.programmer.framework.spring;

/**
 * IoC 容器的核心接口。
 *
 * <p>定义了 Bean 的获取和管理能力，等价于 Spring 中的 BeanFactory。
 * 所有 IoC 容器都必须实现此接口。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface BeanFactory {

    /**
     * 根据名称获取 Bean。
     *
     * @param beanName Bean 名称
     * @return Bean 实例
     * @throws RuntimeException 如果 Bean 不存在
     */
    Object getBean(String beanName);

    /**
     * 根据类型获取 Bean。
     *
     * @param requiredType Bean 类型
     * @param <T>          Bean 类型
     * @return Bean 实例
     * @throws RuntimeException 如果 Bean 不存在或存在多个匹配
     */
    <T> T getBean(Class<T> requiredType);

    /**
     * 判断容器中是否包含指定名称的 Bean。
     *
     * @param beanName Bean 名称
     * @return true 表示包含
     */
    boolean containsBean(String beanName);

    /**
     * 判断指定 Bean 是否单例。
     *
     * @param beanName Bean 名称
     * @return true 表示单例
     * @throws RuntimeException 如果 Bean 不存在
     */
    boolean isSingleton(String beanName);

    /**
     * 发布应用事件，通知所有匹配的监听器。
     *
     * @param event 应用事件
     */
    void publishEvent(ApplicationEvent event);
}
