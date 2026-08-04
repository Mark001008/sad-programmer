package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Bean 初始化后的回调方法。
 *
 * <p>在 Bean 完成属性注入后、放入容器前调用，
 * 等价于 Spring 中的 @PostConstruct 注解。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniPostConstruct {
}
