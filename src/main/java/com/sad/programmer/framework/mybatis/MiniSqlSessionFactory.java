package com.sad.programmer.framework.mybatis;

import java.sql.Connection;

/**
 * SqlSession 工厂，负责创建 SqlSession 实例。
 *
 * <p>等价于 MyBatis 的 SqlSessionFactory。
 * 每次调用 openSession() 创建一个新的 SqlSession（非线程安全）。</p>
 *
 * <p>最佳实践：
 * <pre>
 * SqlSessionFactory factory = ...; // 全局单例
 * SqlSession session = factory.openSession(); // 每次操作创建新实例
 * try {
 *     UserMapper mapper = session.getMapper(UserMapper.class);
 *     User user = mapper.selectById(1L);
 * } finally {
 *     session.close(); // 必须关闭
 * }
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniSqlSessionFactory {

    /** 配置中心。 */
    private final MiniConfiguration configuration;

    /**
     * 构造 SqlSessionFactory。
     *
     * @param configuration 配置中心
     */
    public MiniSqlSessionFactory(MiniConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * 打开一个新的 SqlSession。
     *
     * @return SqlSession 实例
     */
    public MiniSqlSession openSession() {
        try {
            Connection connection = configuration.getConnection();
            MiniExecutor executor = new MiniExecutor(connection);
            return new MiniSqlSession(configuration, executor, connection);
        } catch (Exception e) {
            throw new RuntimeException("打开 SqlSession 失败", e);
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
