package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要从配置文件注入值的字段。
 *
 * <p>容器会根据 key 从 Properties 配置中查找对应的值并注入，
 * 等价于 Spring 中的 @Value 注解。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniValue {

    /**
     * 配置项的 key，格式如 "app.name"。
     *
     * @return 配置 key
     */
    String value();
}
