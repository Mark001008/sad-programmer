package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Bean 的作用域。
 *
 * <p>等价于 Spring 中的 @Scope 注解，控制 Bean 是单例还是原型。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniScope {

    /**
     * Bean 的作用域类型。
     *
     * @return 作用域字符串
     */
    String value() default "singleton";
}
