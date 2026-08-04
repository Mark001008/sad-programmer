package com.sad.programmer.framework.mybatis;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行器，负责 JDBC 层面的 SQL 执行和结果映射。
 *
 * <p>等价于 MyBatis 的 SimpleExecutor，核心职责：
 * <ul>
 *   <li>创建 PreparedStatement，绑定参数</li>
 *   <li>执行 SQL（query / update）</li>
 *   <li>将 ResultSet 映射为 Java 对象</li>
 * </ul></p>
 *
 * <p>参数绑定流程：
 * <pre>
 * MappedStatement.parameterNames: ["id", "name"]
 * 方法参数: {id: 1, name: "Alice"}
 *   → ps.setObject(1, 1)      // 对应 #{id}
 *   → ps.setObject(2, "Alice") // 对应 #{name}
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniExecutor {

    /** 数据库连接。 */
    private final Connection connection;

    /**
     * 构造执行器。
     *
     * @param connection 数据库连接
     */
    public MiniExecutor(Connection connection) {
        this.connection = connection;
    }

    /**
     * 执行查询 SQL，返回结果列表。
     *
     * @param ms       映射语句
     * @param params   方法参数（支持 Map 或单参数）
     * @param <E>      结果元素类型
     * @return 结果列表
     * @throws Exception SQL 执行异常
     */
    @SuppressWarnings("unchecked")
    public <E> List<E> query(MiniMappedStatement ms, Object params) throws Exception {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(ms.getJdbcSql());
            // 绑定参数
            bindParameters(ps, ms, params);
            // 执行查询
            rs = ps.executeQuery();
            // 映射结果集
            return (List<E>) resultSetToList(rs, ms.getResultType());
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    /**
     * 执行更新 SQL（INSERT/UPDATE/DELETE），返回影响行数。
     *
     * @param ms     映射语句
     * @param params 方法参数
     * @return 影响行数
     * @throws Exception SQL 执行异常
     */
    public int update(MiniMappedStatement ms, Object params) throws Exception {
        PreparedStatement ps = null;
        try {
            // 如果是 INSERT 且需要返回自增主键
            if (ms.getSqlType() == MiniMappedStatement.SqlType.INSERT) {
                ps = connection.prepareStatement(ms.getJdbcSql(),
                        Statement.RETURN_GENERATED_KEYS);
            } else {
                ps = connection.prepareStatement(ms.getJdbcSql());
            }
            // 绑定参数
            bindParameters(ps, ms, params);
            // 执行更新
            int rows = ps.executeUpdate();
            return rows;
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * 绑定 PreparedStatement 参数。
     *
     * <p>根据 MappedStatement 中的参数名称，从方法参数中提取值并设置到 PreparedStatement。
     * 支持两种参数形式：
     * <ul>
     *   <li>Map 参数：直接从 Map 中按名称取值</li>
     *   <li>单参数：从对象的字段中按名称取值</li>
     * </ul></p>
     *
     * @param ps       PreparedStatement
     * @param ms       映射语句
     * @param params   方法参数
     * @throws Exception 参数绑定异常
     */
    private void bindParameters(PreparedStatement ps, MiniMappedStatement ms,
                                Object params) throws Exception {
        List<String> paramNames = ms.getParameterNames();
        for (int i = 0; i < paramNames.size(); i++) {
            Object value = extractParam(params, paramNames.get(i));
            ps.setObject(i + 1, value);
        }
    }

    /**
     * 从方法参数中提取指定名称的参数值。
     *
     * @param params   方法参数
     * @param paramName 参数名称
     * @return 参数值
     * @throws Exception 提取失败
     */
    private Object extractParam(Object params, String paramName) throws Exception {
        if (params instanceof Map) {
            return ((Map<?, ?>) params).get(paramName);
        }
        // 从对象字段中提取
        Field field = params.getClass().getDeclaredField(paramName);
        field.setAccessible(true);
        return field.get(params);
    }

    /**
     * 将 ResultSet 映射为对象列表。
     *
     * <p>映射规则：
     * <ol>
     *   <li>获取 ResultSet 的列名</li>
     *   <li>在目标类中查找同名字段（支持下划线转驼峰）</li>
     *   <li>通过反射设置字段值</li>
     * </ol></p>
     *
     * @param rs         结果集
     * @param resultType 目标类型
     * @return 对象列表
     * @throws Exception 映射异常
     */
    private List<?> resultSetToList(ResultSet rs, Class<?> resultType) throws Exception {
        List<Object> list = new ArrayList<Object>();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        while (rs.next()) {
            Object obj = resultType.newInstance();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = meta.getColumnLabel(i);
                // 尝试直接匹配字段名
                Field field = findField(resultType, columnName);
                if (field != null) {
                    field.setAccessible(true);
                    Object value = rs.getObject(i);
                    field.set(obj, value);
                }
            }
            list.add(obj);
        }
        return list;
    }

    /**
     * 在类中查找匹配的字段（支持下划线转驼峰）。
     *
     * @param clazz      目标类
     * @param columnName 列名
     * @return 匹配的字段，未找到返回 null
     */
    private Field findField(Class<?> clazz, String columnName) {
        // 先尝试直接匹配
        try {
            return clazz.getDeclaredField(columnName);
        } catch (NoSuchFieldException ignored) {
            // 下划线转驼峰
        }

        // 下划线转驼峰：user_name → userName
        String camelName = underscoreToCamel(columnName);
        try {
            return clazz.getDeclaredField(camelName);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    /**
     * 下划线命名转驼峰命名。
     *
     * @param underscore 下划线命名
     * @return 驼峰命名
     */
    private String underscoreToCamel(String underscore) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : underscore.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                sb.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            }
        }
        return sb.toString();
    }

    /**
     * 安静关闭资源。
     *
     * @param closeable 可关闭资源
     */
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        }
    }

    /**
     * 获取连接。
     *
     * @return 数据库连接
     */
    public Connection getConnection() {
        return connection;
    }
}
