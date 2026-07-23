package com.sad.programmer.database.lock;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL 死锁演示。
 *
 * <p>面试高频考点：InnoDB 行锁死锁的产生和预防。</p>
 *
 * <p>经典死锁场景：</p>
 * <ol>
 *   <li>事务 A 锁定 row 1，事务 B 锁定 row 2</li>
 *   <li>事务 A 尝试锁定 row 2（被 B 阻塞），事务 B 尝试锁定 row 1（被 A 阻塞）</li>
 *   <li>形成循环等待，InnoDB 检测到死锁，回滚其中一个事务</li>
 * </ol>
 *
 * <p>预防策略：</p>
 * <ul>
 *   <li>固定加锁顺序（所有事务按主键升序加锁）</li>
 *   <li>减少事务持有锁的时间</li>
 *   <li>使用较低的隔离级别</li>
 *   <li>添加合理的索引减少锁范围</li>
 * </ul>
 */
public class DeadlockDemo {

    /**
     * 初始化演示表。
     *
     * @throws SQLException 建表失败
     */
    public void initTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS transfer_demo");
            JdbcUtil.execute(conn,
                    "CREATE TABLE transfer_demo (" +
                    "  id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                    "  account_no VARCHAR(32) NOT NULL," +
                    "  balance_cents BIGINT NOT NULL," +
                    "  UNIQUE KEY uk_account_no (account_no)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transfer_demo (account_no, balance_cents) VALUES (?, ?)")) {
                ps.setString(1, "A");
                ps.setLong(2, 10000);
                ps.executeUpdate();
                ps.setString(1, "B");
                ps.setLong(2, 10000);
                ps.executeUpdate();
                ps.setString(1, "C");
                ps.setLong(2, 10000);
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
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS transfer_demo");
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 获取连接（关闭自动提交）。
     *
     * @return 手动提交的连接
     * @throws SQLException 连接失败
     */
    public Connection getConnection() throws SQLException {
        Connection conn = JdbcUtil.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 锁定指定账户行（SELECT ... FOR UPDATE）。
     *
     * @param conn      数据库连接
     * @param accountNo 账户号
     * @return 当前余额
     * @throws SQLException 查询失败
     */
    public long lockAndRead(Connection conn, String accountNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance_cents FROM transfer_demo WHERE account_no = ? FOR UPDATE")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Account not found: " + accountNo);
                }
                return rs.getLong("balance_cents");
            }
        }
    }

    /**
     * 更新账户余额。
     *
     * @param conn         数据库连接
     * @param accountNo    账户号
     * @param newBalance   新余额
     * @throws SQLException 更新失败
     */
    public void updateBalance(Connection conn, String accountNo, long newBalance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE transfer_demo SET balance_cents = ? WHERE account_no = ?")) {
            ps.setLong(1, newBalance);
            ps.setString(2, accountNo);
            ps.executeUpdate();
        }
    }

    /**
     * 读取账户余额（不加锁）。
     *
     * @param conn      数据库连接
     * @param accountNo 账户号
     * @return 余额
     * @throws SQLException 查询失败
     */
    public long readBalance(Connection conn, String accountNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance_cents FROM transfer_demo WHERE account_no = ?")) {
            ps.setString(1, accountNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Account not found: " + accountNo);
                }
                return rs.getLong("balance_cents");
            }
        }
    }
}
