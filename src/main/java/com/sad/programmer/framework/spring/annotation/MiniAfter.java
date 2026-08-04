package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记后置通知方法。
 *
 * <p>在目标方法执行后执行（无论是否异常），等价于 Spring 中的 @After。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniAfter {

    /**
     * 切入点表达式。
     *
     * @return 切入点表达式
     */
    String value();
}
