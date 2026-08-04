package com.sad.programmer.framework.mybatis;

import com.sad.programmer.framework.mybatis.domain.User;
import com.sad.programmer.framework.mybatis.mapper.UserMapper;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.*;

/**
 * MiniMyBatis 测试。
 *
 * <p>验证注解解析、Mapper 代理、SQL 参数绑定等核心能力。
 * 不依赖真实数据库，通过 Configuration 解析验证框架正确性。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MyBatisTest {

    // ========== 配置解析测试 ==========

    /**
     * 验证 Mapper 注解解析：@MiniSelect 生成正确的 MappedStatement。
     */
    @Test
    public void shouldParseSelectAnnotationCorrectly() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        // 验证 selectById 被解析
        MiniMappedStatement ms = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.selectById");
        assertNotNull("selectById 应被解析", ms);
        assertEquals("SQL 类型应为 SELECT", MiniMappedStatement.SqlType.SELECT, ms.getSqlType());
        assertEquals("返回类型应为 User", User.class, ms.getResultType());
        assertEquals("参数应为 [id]", 1, ms.getParameterNames().size());
        assertEquals("参数名应为 id", "id", ms.getParameterNames().get(0));
    }

    /**
     * 验证 #{} 占位符替换为 ?。
     */
    @Test
    public void shouldReplaceParamPlaceholderWithQuestionMark() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniMappedStatement ms = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.selectById");
        assertEquals("JDBC SQL 应将 #{} 替换为 ?",
                "SELECT * FROM mini_user WHERE id = ?", ms.getJdbcSql());
    }

    /**
     * 验证 INSERT 注解解析：多参数 SQL。
     */
    @Test
    public void shouldParseInsertAnnotationWithMultipleParams() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniMappedStatement ms = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.insert");
        assertNotNull("insert 应被解析", ms);
        assertEquals("SQL 类型应为 INSERT", MiniMappedStatement.SqlType.INSERT, ms.getSqlType());
        assertEquals("应有 3 个参数", 3, ms.getParameterNames().size());
        assertEquals("第一个参数应为 name", "name", ms.getParameterNames().get(0));
        assertEquals("第二个参数应为 email", "email", ms.getParameterNames().get(1));
        assertEquals("第三个参数应为 age", "age", ms.getParameterNames().get(2));
    }

    /**
     * 验证 List 返回类型解析。
     */
    @Test
    public void shouldParseListReturnType() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniMappedStatement ms = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.selectAll");
        assertNotNull("selectAll 应被解析", ms);
        assertEquals("List<User> 的元素类型应为 User", User.class, ms.getResultType());
    }

    /**
     * 验证 UPDATE 和 DELETE 注解解析。
     */
    @Test
    public void shouldParseUpdateAndDeleteAnnotations() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniMappedStatement updateMs = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.update");
        assertNotNull("update 应被解析", updateMs);
        assertEquals("SQL 类型应为 UPDATE", MiniMappedStatement.SqlType.UPDATE, updateMs.getSqlType());

        MiniMappedStatement deleteMs = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.deleteById");
        assertNotNull("deleteById 应被解析", deleteMs);
        assertEquals("SQL 类型应为 DELETE", MiniMappedStatement.SqlType.DELETE, deleteMs.getSqlType());
    }

    // ========== Mapper 代理测试 ==========

    /**
     * 验证 getMapper 返回的是 JDK 动态代理。
     */
    @Test
    public void shouldReturnJdkDynamicProxy() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        // 创建 SqlSession（不需要真实连接，只测试代理创建）
        MiniSqlSessionFactory factory = new MiniSqlSessionFactory(config);

        // 通过反射创建 SqlSession（不调用 openSession，避免连接失败）
        MiniSqlSession session = new MiniSqlSession(config, null, null);
        UserMapper mapper = session.getMapper(UserMapper.class);

        assertNotNull("Mapper 不应为 null", mapper);
        assertTrue("Mapper 应为 JDK 动态代理",
                Proxy.isProxyClass(mapper.getClass()));
    }

    /**
     * 验证 Mapper 代理实现了正确的接口。
     */
    @Test
    public void shouldImplementCorrectInterface() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniSqlSession session = new MiniSqlSession(config, null, null);
        UserMapper mapper = session.getMapper(UserMapper.class);

        assertTrue("Mapper 应实现 UserMapper 接口",
                mapper instanceof UserMapper);
    }

    // ========== MappedStatement 完整性测试 ==========

    /**
     * 验证所有 Mapper 方法都被解析。
     */
    @Test
    public void shouldParseAllMapperMethods() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        // UserMapper 有 6 个方法
        assertEquals("应解析 6 个映射语句", 6, config.getMappedStatements().size());
    }

    /**
     * 验证原始 SQL 保留不变。
     */
    @Test
    public void shouldPreserveRawSql() {
        MiniConfiguration config = new MiniConfiguration("jdbc:mysql://localhost/test", "root", "");
        config.addMapper(UserMapper.class);

        MiniMappedStatement ms = config.getMappedStatement(
                "com.sad.programmer.framework.mybatis.mapper.UserMapper.selectById");
        assertTrue("原始 SQL 应包含 #{}",
                ms.getRawSql().contains("#{id}"));
        assertFalse("JDBC SQL 不应包含 #{}",
                ms.getJdbcSql().contains("#{}"));
    }
}
