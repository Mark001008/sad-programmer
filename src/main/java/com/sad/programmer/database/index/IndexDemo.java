package com.sad.programmer.database.index;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MySQL 索引演示。
 *
 * <p>面试高频考点：</p>
 * <ul>
 *   <li>B+Tree 索引结构（聚簇索引 vs 二级索引）</li>
 *   <li>最左前缀原则</li>
 *   <li>覆盖索引 vs 回表</li>
 *   <li>索引失效场景（函数、隐式转换、LIKE '%xx'）</li>
 * </ul>
 */
public class IndexDemo {

    /**
     * 初始化用户订单表（演示索引用）。
     *
     * <p>表结构设计：</p>
     * <ul>
     *   <li>id：主键（聚簇索引）</li>
     *   <li>user_id + created_at：联合索引（演示最左前缀）</li>
     *   <li>order_no：唯一索引</li>
     *   <li>status：普通索引</li>
     * </ul>
     *
     * @throws SQLException 建表失败
     */
    public void initTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS order_demo");
            JdbcUtil.execute(conn,
                    "CREATE TABLE order_demo (" +
                    "  id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                    "  order_no VARCHAR(32) NOT NULL," +
                    "  user_id BIGINT NOT NULL," +
                    "  amount_cents BIGINT NOT NULL," +
                    "  status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已发货 3-已完成'," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  UNIQUE KEY uk_order_no (order_no)," +
                    "  KEY idx_user_created (user_id, created_at)," +
                    "  KEY idx_status (status)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 批量插入订单数据。
     *
     * @param count 插入数量
     * @throws SQLException 插入失败
     */
    public void bulkInsert(int count) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO order_demo (order_no, user_id, amount_cents, status) VALUES (?, ?, ?, ?)")) {
                for (int i = 1; i <= count; i++) {
                    ps.setString(1, "ORD" + String.format("%08d", i));
                    ps.setLong(2, (i % 100) + 1);  // 100 个用户
                    ps.setLong(3, (long) (Math.random() * 100000));
                    ps.setInt(4, i % 4);
                    ps.addBatch();
                    if (i % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            conn.commit();
        } finally {
            if (conn != null) {
                conn.setAutoCommit(true);
                JdbcUtil.close(conn);
            }
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
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS order_demo");
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 使用 EXPLAIN 分析查询计划。
     *
     * @param sql 查询语句
     * @return EXPLAIN 结果的 key 列（使用的索引名）
     * @throws SQLException 查询失败
     */
    public String explainKey(String sql) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
                if (rs.next()) {
                    return rs.getString("key");
                }
                return null;
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 使用 EXPLAIN 分析查询计划，返回 type 列。
     *
     * @param sql 查询语句
     * @return EXPLAIN 结果的 type 列（访问类型）
     * @throws SQLException 查询失败
     */
    public String explainType(String sql) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
                if (rs.next()) {
                    return rs.getString("type");
                }
                return null;
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 使用 EXPLAIN 分析查询计划，返回 Extra 列。
     *
     * @param sql 查询语句
     * @return EXPLAIN 结果的 Extra 列（额外信息，如 Using index）
     * @throws SQLException 查询失败
     */
    public String explainExtra(String sql) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
                if (rs.next()) {
                    return rs.getString("Extra");
                }
                return null;
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }
}
