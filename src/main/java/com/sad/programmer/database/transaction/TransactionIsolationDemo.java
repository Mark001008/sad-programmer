package com.sad.programmer.database.transaction;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL 事务隔离级别演示。
 *
 * <p>面试高频考点：四种隔离级别下会出现的并发问题。</p>
 * <ul>
 *   <li>READ UNCOMMITTED → 脏读（Dirty Read）</li>
 *   <li>READ COMMITTED → 不可重复读（Non-Repeatable Read）</li>
 *   <li>REPEATABLE READ → 幻读（Phantom Read）*MySQL 默认</li>
 *   <li>SERIALIZABLE → 完全隔离，性能最差</li>
 * </ul>
 *
 * <p>使用 account_demo 表模拟银行账户，通过两个并发连接演示各种现象。</p>
 */
public class TransactionIsolationDemo {

    /** 演示用的表名。 */
    private static final String TABLE = "account_demo";

    /**
     * 初始化演示表。
     *
     * <p>创建 account_demo 表并插入初始数据。</p>
     *
     * @throws SQLException 建表失败
     */
    public void initTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS " + TABLE);
            JdbcUtil.execute(conn,
                    "CREATE TABLE " + TABLE + " (" +
                    "  id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                    "  account_no VARCHAR(32) NOT NULL," +
                    "  balance_cents BIGINT NOT NULL," +
                    "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TABLE + " (account_no, balance_cents) VALUES (?, ?)")) {
                ps.setString(1, "A");
                ps.setLong(2, 10000);
                ps.executeUpdate();
                ps.setString(1, "B");
                ps.setLong(2, 5000);
                ps.executeUpdate();
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 清理演示表。
     *
     * @throws SQLException 删表失败
     */
    public void dropTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS " + TABLE);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 读取指定账户的余额。
     *
     * @param conn      数据库连接
     * @param accountNo 账户号
     * @return 余额（分），不存在时返回 -1
     * @throws SQLException 查询失败
     */
    public long readBalance(Connection conn, String accountNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance_cents FROM " + TABLE + " WHERE account_no = ?")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance_cents") : -1;
            }
        }
    }

    /**
     * 更新指定账户的余额。
     *
     * <p>调用方负责事务管理（commit/rollback）。</p>
     *
     * @param conn         数据库连接
     * @param accountNo    账户号
     * @param newBalanceCents 新余额（分）
     * @throws SQLException 更新失败
     */
    public void updateBalance(Connection conn, String accountNo, long newBalanceCents) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + TABLE + " SET balance_cents = ? WHERE account_no = ?")) {
            ps.setLong(1, newBalanceCents);
            ps.setString(2, accountNo);
            ps.executeUpdate();
        }
    }

    /**
     * 统计账户表中的账户数量。
     *
     * @param conn 数据库连接
     * @return 账户数量
     * @throws SQLException 查询失败
     */
    public int countAccounts(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 插入一个新账户。
     *
     * <p>调用方负责事务管理。</p>
     *
     * @param conn         数据库连接
     * @param accountNo    账户号
     * @param balanceCents 初始余额
     * @throws SQLException 插入失败
     */
    public void insertAccount(Connection conn, String accountNo, long balanceCents) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + " (account_no, balance_cents) VALUES (?, ?)")) {
            ps.setString(1, accountNo);
            ps.setLong(2, balanceCents);
            ps.executeUpdate();
        }
    }

    /**
     * 获取指定隔离级别的连接。
     *
     * @param level java.sql.Connection 常量
     * @return 数据库连接
     * @throws SQLException 连接失败
     */
    public Connection getConnection(int level) throws SQLException {
        return JdbcUtil.getConnection(level);
    }

    /**
     * 获取默认连接。
     *
     * @return 数据库连接
     * @throws SQLException 连接失败
     */
    public Connection getConnection() throws SQLException {
        return JdbcUtil.getConnection();
    }
}
