package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记前置通知方法。
 *
 * <p>在目标方法执行前执行，等价于 Spring 中的 @Before。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniBefore {

    /**
     * 切入点表达式，格式为 "类名.方法名"，支持通配符 *。
     * <p>例如："OrderService.*" 表示 OrderService 的所有方法。</p>
     *
     * @return 切入点表达式
     */
    String value();
}
