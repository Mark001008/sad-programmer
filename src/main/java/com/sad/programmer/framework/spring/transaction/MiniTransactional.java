package com.sad.programmer.framework.spring.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式事务注解，标注在类或方法上表示需要事务管理。
 *
 * <p>等价于 Spring 的 @Transactional，核心原理：
 * <ol>
 *   <li>容器扫描到 @MiniTransactional 后，为该 Bean 创建 JDK 动态代理</li>
 *   <li>代理拦截方法调用，在方法执行前通过 ThreadLocal 绑定数据库连接</li>
 *   <li>方法正常返回时提交事务，抛出异常时回滚事务</li>
 *   <li>最终释放连接，清除 ThreadLocal，防止内存泄漏</li>
 * </ol></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MiniTransactional {
}
