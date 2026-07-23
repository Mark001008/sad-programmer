package com.sad.programmer.database.pagination;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 分页查询演示。
 *
 * <p>面试高频考点：深度分页性能优化。</p>
 *
 * <p>两种分页方式对比：</p>
 * <ul>
 *   <li>OFFSET 分页：SELECT ... LIMIT offset, size — 简单但深度分页慢（扫描并丢弃 offset 行）</li>
 *   <li>游标分页：WHERE id > lastId LIMIT size — 深度分页也快（利用主键索引定位）</li>
 * </ul>
 *
 * <p>优化技巧：</p>
 * <ul>
 *   <li>延迟关联：先查主键再 JOIN 取完整行</li>
 *   <li>覆盖索引：只查索引中已有的列</li>
 *   <li>游标分页：适用于瀑布流/无限滚动场景</li>
 * </ul>
 */
public class PaginationDemo {

    /**
     * 初始化商品表。
     *
     * @throws SQLException 建表失败
     */
    public void initTable() throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS product_demo");
            JdbcUtil.execute(conn,
                    "CREATE TABLE product_demo (" +
                    "  id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                    "  name VARCHAR(128) NOT NULL," +
                    "  category VARCHAR(64) NOT NULL," +
                    "  price_cents BIGINT NOT NULL," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  KEY idx_category_price (category, price_cents)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 批量插入商品数据。
     *
     * @param count 插入数量
     * @throws SQLException 插入失败
     */
    public void bulkInsert(int count) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            conn.setAutoCommit(false);
            String[] categories = {"electronics", "clothing", "food", "books", "home"};
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO product_demo (name, category, price_cents) VALUES (?, ?, ?)")) {
                for (int i = 1; i <= count; i++) {
                    ps.setString(1, "Product-" + i);
                    ps.setString(2, categories[i % categories.length]);
                    ps.setLong(3, (long) (Math.random() * 100000));
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
            JdbcUtil.execute(conn, "DROP TABLE IF EXISTS product_demo");
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * OFFSET 分页查询。
     *
     * <p>SELECT * FROM product_demo ORDER BY id LIMIT offset, size</p>
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 商品 ID 列表
     * @throws SQLException 查询失败
     */
    public List<Long> offsetPagination(int page, int size) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            int offset = (page - 1) * size;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM product_demo ORDER BY id LIMIT ?, ?")) {
                ps.setInt(1, offset);
                ps.setInt(2, size);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<Long>();
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                    }
                    return ids;
                }
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 游标分页查询（基于主键 ID）。
     *
     * <p>SELECT * FROM product_demo WHERE id > lastId ORDER BY id LIMIT size</p>
     *
     * <p>优势：无论第几页，查询性能恒定（利用主键索引直接定位）。</p>
     *
     * @param lastId 上一页最后一条的 ID（首页传 0）
     * @param size   每页条数
     * @return 商品 ID 列表
     * @throws SQLException 查询失败
     */
    public List<Long> cursorPagination(long lastId, int size) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM product_demo WHERE id > ? ORDER BY id LIMIT ?")) {
                ps.setLong(1, lastId);
                ps.setInt(2, size);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<Long>();
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                    }
                    return ids;
                }
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 延迟关联优化的深度分页。
     *
     * <p>先通过覆盖索引查出主键，再 JOIN 取完整行。
     * 避免了 OFFSET 深度分页时回表大量行的问题。</p>
     *
     * <p>优化前：SELECT * FROM product_demo ORDER BY id LIMIT 100000, 10（扫描 100010 行）</p>
     <p>优化后：
     * SELECT p.* FROM product_demo p
     * INNER JOIN (SELECT id FROM product_demo ORDER BY id LIMIT 100000, 10) t
     * ON p.id = t.id</p>
     *
     * @param offset 偏移量
     * @param size   每页条数
     * @return 商品 ID 列表
     * @throws SQLException 查询失败
     */
    public List<Long> deferredJoinPagination(int offset, int size) throws SQLException {
        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT p.id FROM product_demo p " +
                    "INNER JOIN (SELECT id FROM product_demo ORDER BY id LIMIT ?, ?) t " +
                    "ON p.id = t.id")) {
                ps.setInt(1, offset);
                ps.setInt(2, size);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<Long>();
                    while (rs.next()) {
                        ids.add(rs.getLong("id"));
                    }
                    return ids;
                }
            }
        } finally {
            JdbcUtil.close(conn);
        }
    }
}
