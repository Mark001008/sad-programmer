package com.sad.programmer.framework.mybatis;

import java.util.List;

/**
 * 映射语句，封装一条 SQL 的完整信息。
 *
 * <p>等价于 MyBatis 的 MappedStatement，包含：
 * <ul>
 *   <li>原始 SQL（带 #{} 占位符）</li>
 *   <li>转换后的 JDBC SQL（带 ? 占位符）</li>
 *   <li>参数名称列表（按顺序）</li>
 *   <li>SQL 类型（SELECT/INSERT/UPDATE/DELETE）</li>
 *   <li>返回类型</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniMappedStatement {

    /** SQL 类型枚举。 */
    public enum SqlType {
        /** 查询。 */
        SELECT,
        /** 插入。 */
        INSERT,
        /** 更新。 */
        UPDATE,
        /** 删除。 */
        DELETE
    }

    /** 唯一标识：mapper 全限定名 + 方法名。 */
    private final String id;

    /** 原始 SQL（包含 #{} 占位符）。 */
    private final String rawSql;

    /** 转换后的 JDBC SQL（#{} 替换为 ?）。 */
    private final String jdbcSql;

    /** 参数名称列表（按 #{} 出现顺序）。 */
    private final List<String> parameterNames;

    /** SQL 类型。 */
    private final SqlType sqlType;

    /** 返回类型。 */
    private final Class<?> resultType;

    /**
     * 构造映射语句。
     *
     * @param id             唯一标识
     * @param rawSql         原始 SQL
     * @param jdbcSql        转换后的 JDBC SQL
     * @param parameterNames 参数名称列表
     * @param sqlType        SQL 类型
     * @param resultType     返回类型
     */
    public MiniMappedStatement(String id, String rawSql, String jdbcSql,
                               List<String> parameterNames, SqlType sqlType,
                               Class<?> resultType) {
        this.id = id;
        this.rawSql = rawSql;
        this.jdbcSql = jdbcSql;
        this.parameterNames = parameterNames;
        this.sqlType = sqlType;
        this.resultType = resultType;
    }

    /**
     * 获取唯一标识。
     *
     * @return 唯一标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取原始 SQL。
     *
     * @return 原始 SQL
     */
    public String getRawSql() {
        return rawSql;
    }

    /**
     * 获取 JDBC SQL。
     *
     * @return JDBC SQL
     */
    public String getJdbcSql() {
        return jdbcSql;
    }

    /**
     * 获取参数名称列表。
     *
     * @return 参数名称列表
     */
    public List<String> getParameterNames() {
        return parameterNames;
    }

    /**
     * 获取 SQL 类型。
     *
     * @return SQL 类型
     */
    public SqlType getSqlType() {
        return sqlType;
    }

    /**
     * 获取返回类型。
     *
     * @return 返回类型
     */
    public Class<?> getResultType() {
        return resultType;
    }
}
