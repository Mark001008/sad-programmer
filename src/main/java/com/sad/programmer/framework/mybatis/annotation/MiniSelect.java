package com.sad.programmer.framework.mybatis.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 查询语句注解，标注在 Mapper 接口方法上。
 *
 * <p>等价于 MyBatis 的 @Select，value 中的 SQL 使用 #{} 作为参数占位符。
 * 运行时由 MapperProxy 解析并替换为 JDBC 的 ? 占位符。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * public interface UserMapper {
 *     @MiniSelect("SELECT * FROM users WHERE id = #{id}")
 *     User selectById(Long id);
 * }
 * }</pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniSelect {

    /**
     * SQL 查询语句。
     *
     * @return SQL 字符串
     */
    String value();
}
