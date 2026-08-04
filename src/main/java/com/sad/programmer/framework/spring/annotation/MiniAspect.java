package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为切面。
 *
 * <p>被标记的类中的 @MiniBefore、@MiniAfter、@MiniAround 方法
 * 会在目标方法执行前后被织入，等价于 Spring 中的 @Aspect。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniAspect {
}
