package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为 Spring 容器管理的组件。
 *
 * <p>被标记的类会被 IoC 容器扫描并注册为 Bean，
 * 等价于 Spring 中的 @Component 注解。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniComponent {

    /**
     * Bean 的名称，默认为首字母小写的类名。
     *
     * @return Bean 名称
     */
    String value() default "";
}
