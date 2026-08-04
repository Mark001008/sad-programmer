package com.sad.programmer.framework.mybatis;

import com.sad.programmer.framework.mybatis.annotation.MiniDelete;
import com.sad.programmer.framework.mybatis.annotation.MiniInsert;
import com.sad.programmer.framework.mybatis.annotation.MiniSelect;
import com.sad.programmer.framework.mybatis.annotation.MiniUpdate;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 配置中心，持有所有映射语句和 Mapper 注册信息。
 *
 * <p>等价于 MyBatis 的 Configuration，职责：
 * <ul>
 *   <li>解析 Mapper 接口上的注解，生成 MappedStatement</li>
 *   <li>管理 Mapper 接口与代理的映射关系</li>
 *   <li>管理数据库连接信息</li>
 * </ul></p>
 *
 * <p>#{} 占位符解析规则：
 * <pre>
 * "SELECT * FROM users WHERE id = #{id} AND name = #{name}"
 *   → jdbcSql: "SELECT * FROM users WHERE id = ? AND name = ?"
 *   → parameterNames: ["id", "name"]
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniConfiguration {

    /** 映射语句注册表：statementId → MappedStatement。 */
    private final Map<String, MiniMappedStatement> mappedStatements = new HashMap<String, MiniMappedStatement>();

    /** Mapper 接口注册表：mapperClass → 代理实例。 */
    private final Map<Class<?>, Object> mapperRegistry = new HashMap<Class<?>, Object>();

    /** #{} 占位符正则。 */
    private static final Pattern PARAM_PATTERN = Pattern.compile("#\\{([^}]+)}");

    /** 数据库连接 URL。 */
    private String jdbcUrl;

    /** 数据库用户名。 */
    private String username;

    /** 数据库密码。 */
    private String password;

    /**
     * 构造配置中心。
     *
     * @param jdbcUrl  数据库连接 URL
     * @param username 用户名
     * @param password 密码
     */
    public MiniConfiguration(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * 获取数据库连接。
     *
     * @return JDBC 连接
     * @throws Exception 连接失败
     */
    public Connection getConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * 注册 Mapper 接口，解析其上的注解生成 MappedStatement。
     *
     * <p>解析流程：
     * <ol>
     *   <li>遍历 Mapper 接口的所有方法</li>
     *   <li>检查方法上的 @MiniSelect/@MiniInsert/@MiniUpdate/@MiniDelete 注解</li>
     *   <li>解析 SQL 中的 #{} 占位符，生成参数名称列表</li>
     *   <li>将 #{} 替换为 ?，生成 JDBC SQL</li>
     *   <li>创建 MappedStatement 并注册</li>
     * </ol></p>
     *
     * @param mapperClass Mapper 接口类
     */
    public void addMapper(Class<?> mapperClass) {
        String namespace = mapperClass.getName();

        for (Method method : mapperClass.getMethods()) {
            String sql = null;
            MiniMappedStatement.SqlType sqlType = null;

            // 解析注解，获取 SQL 和类型
            if (method.isAnnotationPresent(MiniSelect.class)) {
                sql = method.getAnnotation(MiniSelect.class).value();
                sqlType = MiniMappedStatement.SqlType.SELECT;
            } else if (method.isAnnotationPresent(MiniInsert.class)) {
                sql = method.getAnnotation(MiniInsert.class).value();
                sqlType = MiniMappedStatement.SqlType.INSERT;
            } else if (method.isAnnotationPresent(MiniUpdate.class)) {
                sql = method.getAnnotation(MiniUpdate.class).value();
                sqlType = MiniMappedStatement.SqlType.UPDATE;
            } else if (method.isAnnotationPresent(MiniDelete.class)) {
                sql = method.getAnnotation(MiniDelete.class).value();
                sqlType = MiniMappedStatement.SqlType.DELETE;
            }

            if (sql == null) {
                continue;
            }

            // 解析 #{} 占位符
            List<String> paramNames = new ArrayList<String>();
            Matcher matcher = PARAM_PATTERN.matcher(sql);
            while (matcher.find()) {
                paramNames.add(matcher.group(1));
            }

            // 替换 #{} 为 ?
            String jdbcSql = PARAM_PATTERN.matcher(sql).replaceAll("?");

            // 解析返回类型
            Class<?> resultType = resolveResultType(method);

            // 注册映射语句
            String statementId = namespace + "." + method.getName();
            MiniMappedStatement ms = new MiniMappedStatement(
                    statementId, sql, jdbcSql, paramNames, sqlType, resultType);
            mappedStatements.put(statementId, ms);
        }
    }

    /**
     * 解析方法的返回类型。
     *
     * <p>支持：
     * <ul>
     *   <li>直接返回类型：User → User.class</li>
     *   <li>泛型集合：List&lt;User&gt; → User.class</li>
     * </ul></p>
     *
     * @param method Mapper 方法
     * @return 结果元素类型
     */
    private Class<?> resolveResultType(Method method) {
        Class<?> returnType = method.getReturnType();

        // 如果返回 List，解析泛型参数
        if (List.class.isAssignableFrom(returnType)) {
            Type genericType = method.getGenericReturnType();
            if (genericType instanceof ParameterizedType) {
                Type[] typeArgs = ((ParameterizedType) genericType).getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
        }

        return returnType;
    }

    /**
     * 获取映射语句。
     *
     * @param statementId 唯一标识
     * @return 映射语句
     */
    public MiniMappedStatement getMappedStatement(String statementId) {
        return mappedStatements.get(statementId);
    }

    /**
     * 获取所有映射语句。
     *
     * @return 映射语句表
     */
    public Map<String, MiniMappedStatement> getMappedStatements() {
        return mappedStatements;
    }
}
