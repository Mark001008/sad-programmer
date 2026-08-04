package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记环绕通知方法。
 *
 * <p>环绕目标方法执行，可以控制目标方法是否执行、修改返回值等，
 * 等价于 Spring 中的 @Around。方法签名必须为：
 * {@code public Object methodName(ProceedingJoinPoint joinPoint)}。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniAround {

    /**
     * 切入点表达式。
     *
     * @return 切入点表达式
     */
    String value();
}
