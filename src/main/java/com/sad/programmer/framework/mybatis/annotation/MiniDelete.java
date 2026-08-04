package com.sad.programmer.framework.mybatis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 删除语句注解，标注在 Mapper 接口方法上。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniDelete {

    /**
     * SQL 删除语句。
     *
     * @return SQL 字符串
     */
    String value();
}
