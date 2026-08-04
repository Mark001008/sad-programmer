package com.sad.programmer.framework.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要自动注入的字段或构造方法参数。
 *
 * <p>容器会按类型从 IoC 容器中查找匹配的 Bean 并注入，
 * 等价于 Spring 中的 @Autowired 注解。</p>
 *
 * <p>注入策略：
 * <ul>
 *   <li>先按类型查找，找到唯一匹配直接注入</li>
 *   <li>找到多个匹配，再按字段名作为 Bean 名称二次匹配</li>
 *   <li>找不到且 required=true，抛出异常</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Documented
@Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniAutowired {

    /**
     * 是否必须注入，默认 true。设为 false 时找不到不报错。
     *
     * @return 是否必须
     */
    boolean required() default true;
}
