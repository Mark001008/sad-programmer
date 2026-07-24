package com.sad.programmer.database.common;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * JDBC 工具类。
 *
 * <p>从 classpath 下的 db.properties 读取连接配置。
 * 练习用，不使用连接池，方便理解原生 JDBC 流程。</p>
 *
 * @author sad-programmer
 */
public final class JdbcUtil {

    /** 数据库连接 URL */
    private static final String URL;

    /** 数据库用户名 */
    private static final String USERNAME;

    /** 数据库密码 */
    private static final String PASSWORD;

    static {
        Properties props = new Properties();
        try (InputStream is = JdbcUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (is == null) {
                throw new IllegalStateException("db.properties not found in classpath");
            }
            props.load(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load db.properties", e);
        }
        try {
            Class.forName(props.getProperty("db.driver"));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL JDBC driver not found", e);
        }
        URL = props.getProperty("db.url");
        USERNAME = props.getProperty("db.username");
        PASSWORD = props.getProperty("db.password");
    }

    /**
     * 私有构造方法，防止工具类被实例化。
     *
     * @throws UnsupportedOperationException 始终抛出
     */
    private JdbcUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 获取数据库连接。
     *
     * @return 新的数据库连接
     * @throws SQLException 连接失败
     */
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    /**
     * 获取指定事务隔离级别的连接。
     *
     * @param level 隔离级别常量（如 {@link Connection#TRANSACTION_REPEATABLE_READ}）
     * @return 新的数据库连接
     * @throws SQLException 连接失败
     */
    public static Connection getConnection(int level) throws SQLException {
        Connection conn = DriverManager.getConnection(URL, USERNAME, PASSWORD);
        conn.setTransactionIsolation(level);
        return conn;
    }

    /**
     * 关闭可关闭资源，忽略关闭异常。
     *
     * @param resources 待关闭的资源列表，允许 null 元素
     */
    public static void close(AutoCloseable... resources) {
        for (AutoCloseable r : resources) {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 执行 DDL 语句。
     *
     * @param conn 数据库连接
     * @param sql  DDL 语句
     * @throws SQLException 执行失败
     */
    public static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
