package com.sad.programmer.framework.mybatis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 更新语句注解，标注在 Mapper 接口方法上。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniUpdate {

    /**
     * SQL 更新语句。
     *
     * @return SQL 字符串
     */
    String value();
}
