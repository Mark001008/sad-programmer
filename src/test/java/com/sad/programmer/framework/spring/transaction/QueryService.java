package com.sad.programmer.framework.spring.transaction;

import com.sad.programmer.framework.spring.annotation.MiniComponent;

/**
 * 查询服务实现，无 @MiniTransactional 注解。
 *
 * <p>用于验证：没有 @MiniTransactional 的 Bean 不会被代理，
 * 方法执行时不会开启事务。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
public class QueryService implements IQueryService {

    @Override
    public String query(String key) {
        return "result:" + key;
    }
}
