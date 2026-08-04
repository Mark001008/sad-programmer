package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Bean 销毁前的回调方法。
 *
 * <p>在容器关闭、Bean 被销毁前调用，
 * 等价于 Spring 中的 @PreDestroy 注解。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniPreDestroy {
}
