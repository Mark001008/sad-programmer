package com.sad.programmer.framework.spring.transaction;

/**
 * 查询服务接口，用于测试非事务方法。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public interface IQueryService {

    /**
     * 查询数据（非事务方法）。
     *
     * @param key 查询键
     * @return 查询结果
     */
    String query(String key);
}
