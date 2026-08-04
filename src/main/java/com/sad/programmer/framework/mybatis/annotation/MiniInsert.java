package com.sad.programmer.framework.mybatis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插入语句注解，标注在 Mapper 接口方法上。
 *
 * <p>等价于 MyBatis 的 @Insert，value 中的 SQL 使用 #{} 作为参数占位符。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniInsert {

    /**
     * SQL 插入语句。
     *
     * @return SQL 字符串
     */
    String value();
}
