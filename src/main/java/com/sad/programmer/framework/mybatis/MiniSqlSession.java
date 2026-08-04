package com.sad.programmer.framework.mybatis;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;

/**
 * SqlSession，MyBatis 的核心会话接口。
 *
 * <p>等价于 MyBatis 的 SqlSession，职责：
 * <ul>
 *   <li>getMapper()：获取 Mapper 接口的代理对象</li>
     *   <li>selectOne/selectList：直接执行 SQL</li>
 *   <li>insert/update/delete：执行更新操作</li>
 *   <li>commit/rollback：事务控制</li>
 *   <li>close：关闭连接</li>
 * </ul></p>
 *
 * <p>核心设计：getMapper() 返回的是 JDK 动态代理，调用任何方法都会被
 * {@link MiniMapperProxy} 拦截并转换为 SQL 执行。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniSqlSession {

    /** 配置中心。 */
    private final MiniConfiguration configuration;

    /** SQL 执行器。 */
    private final MiniExecutor executor;

    /** 数据库连接。 */
    private final Connection connection;

    /**
     * 构造 SqlSession。
     *
     * @param configuration 配置中心
     * @param executor      SQL 执行器
     * @param connection    数据库连接
     */
    public MiniSqlSession(MiniConfiguration configuration, MiniExecutor executor,
                          Connection connection) {
        this.configuration = configuration;
        this.executor = executor;
        this.connection = connection;
    }

    /**
     * 获取 Mapper 接口的代理对象。
     *
     * <p>核心方法：通过 JDK 动态代理为 Mapper 接口创建代理实例。
     * 调用代理对象的任何方法都会被 {@link MiniMapperProxy} 拦截。</p>
     *
     * @param mapperClass Mapper 接口类
     * @param <T>         Mapper 类型
     * @return Mapper 代理对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getMapper(Class<T> mapperClass) {
        return (T) Proxy.newProxyInstance(
                mapperClass.getClassLoader(),
                new Class[]{mapperClass},
                new MiniMapperProxy<T>(configuration, mapperClass, executor)
        );
    }

    /**
     * 查询单条记录。
     *
     * @param statementId 映射语句 ID
     * @param params      参数
     * @param <T>         结果类型
     * @return 单条记录，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T selectOne(String statementId, Object params) {
        try {
            MiniMappedStatement ms = configuration.getMappedStatement(statementId);
            List<T> list = executor.query(ms, params);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + statementId, e);
        }
    }

    /**
     * 查询多条记录。
     *
     * @param statementId 映射语句 ID
     * @param params      参数
     * @param <T>         结果类型
     * @return 结果列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> selectList(String statementId, Object params) {
        try {
            MiniMappedStatement ms = configuration.getMappedStatement(statementId);
            return executor.query(ms, params);
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + statementId, e);
        }
    }

    /**
     * 执行更新操作（INSERT/UPDATE/DELETE）。
     *
     * @param statementId 映射语句 ID
     * @param params      参数
     * @return 影响行数
     */
    public int update(String statementId, Object params) {
        try {
            MiniMappedStatement ms = configuration.getMappedStatement(statementId);
            return executor.update(ms, params);
        } catch (Exception e) {
            throw new RuntimeException("更新失败: " + statementId, e);
        }
    }

    /**
     * 提交事务。
     */
    public void commit() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.commit();
            }
        } catch (Exception e) {
            throw new RuntimeException("提交事务失败", e);
        }
    }

    /**
     * 回滚事务。
     */
    public void rollback() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
            }
        } catch (Exception e) {
            throw new RuntimeException("回滚事务失败", e);
        }
    }

    /**
     * 关闭 SqlSession，释放数据库连接。
     *
     * <p>必须在 finally 块中调用，防止连接泄漏。</p>
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("关闭 SqlSession 失败", e);
        }
    }

    /**
     * 获取配置中心。
     *
     * @return 配置中心
     */
    public MiniConfiguration getConfiguration() {
        return configuration;
    }
}
