package com.sad.programmer.business.inventory.sales;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 销售库存数据访问实现类。
 *
 * <p>基于原生 JDBC 实现对 sales_inventory 表的 CRUD 操作。
 * 所有方法均使用 PreparedStatement 防止 SQL 注入，并由调用方
 * 负责 Connection 的事务管理和资源关闭。</p>
 *
 * <p>表结构：
 * <ul>
 *   <li>product_id — 商品ID，BIGINT，唯一索引</li>
 *   <li>available_stock — 可用库存，INT</li>
 *   <li>allocated_stock — 已分配库存，INT</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public class SalesInventoryDaoImpl implements SalesInventoryDao {

    /**
     * sales_inventory 表名常量。
     */
    private static final String TABLE_NAME = "sales_inventory";

    /**
     * SELECT 语句：根据商品ID查询全部字段。
     */
    private static final String SQL_SELECT_BY_PRODUCT_ID =
            "SELECT product_id, available_stock, allocated_stock FROM " + TABLE_NAME
                    + " WHERE product_id = ?";

    /**
     * INSERT 语句：插入新商品库存记录。
     */
    private static final String SQL_INSERT =
            "INSERT INTO " + TABLE_NAME + " (product_id, available_stock, allocated_stock)"
                    + " VALUES (?, ?, 0)";

    /**
     * UPDATE 语句：增量更新可用库存（可正可负）。
     */
    private static final String SQL_UPDATE_STOCK =
            "UPDATE " + TABLE_NAME + " SET available_stock = available_stock + ?"
                    + " WHERE product_id = ?";

    /**
     * UPDATE 语句：带库存校验的扣减操作（乐观锁）。
     */
    private static final String SQL_UPDATE_STOCK_WITH_CHECK =
            "UPDATE " + TABLE_NAME + " SET available_stock = available_stock - ?"
                    + " WHERE product_id = ? AND available_stock >= ?";

    /**
     * {@inheritDoc}
     *
     * <p>执行 SELECT 查询，将结果映射为 {@link SalesInventoryResult} 对象。
     * 若查询结果为空，返回 null。</p>
     */
    @Override
    public SalesInventoryResult findByProductId(final Connection conn, final long productId)
            throws SQLException {
        // 参数校验在方法入口完成
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (conn == null) {
            throw new IllegalArgumentException("数据库连接不能为null");
        }

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(SQL_SELECT_BY_PRODUCT_ID);
            ps.setLong(1, productId);
            rs = ps.executeQuery();
            if (rs.next()) {
                // 从 ResultSet 提取字段值，构造不可变结果对象
                final long pid = rs.getLong("product_id");
                final int availableStock = rs.getInt("available_stock");
                final java.util.Map<String, Integer> channelMap =
                        new java.util.HashMap<String, Integer>();
                channelMap.put("DEFAULT", availableStock);
                return new SalesInventoryResult(pid, availableStock, channelMap);
            }
            return null;
        } finally {
            // 关闭 ResultSet 和 PreparedStatement，Connection 由调用方管理
            closeQuietly(rs, ps);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>执行 INSERT 语句插入新商品库存记录，allocated_stock 默认为 0。</p>
     */
    @Override
    public int insert(final Connection conn, final long productId, final int availableStock)
            throws SQLException {
        // 参数校验在方法入口完成
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (availableStock < 0) {
            throw new IllegalArgumentException("可用库存不能为负数，当前值：" + availableStock);
        }
        if (conn == null) {
            throw new IllegalArgumentException("数据库连接不能为null");
        }

        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(SQL_INSERT);
            ps.setLong(1, productId);
            ps.setInt(2, availableStock);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>执行增量更新 SQL，delta 为正数时增加库存，为负数时减少库存。
     * 若商品记录不存在，返回受影响行数 0。</p>
     */
    @Override
    public int updateAvailableStock(final Connection conn, final long productId, final int delta)
            throws SQLException {
        // 参数校验在方法入口完成
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (conn == null) {
            throw new IllegalArgumentException("数据库连接不能为null");
        }

        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(SQL_UPDATE_STOCK);
            ps.setInt(1, delta);
            ps.setLong(2, productId);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>执行带 WHERE available_stock >= quantity 条件的扣减 SQL。
     * 库存不足时 WHERE 条件不匹配，返回 0。</p>
     */
    @Override
    public int updateAvailableStockWithCheck(final Connection conn, final long productId,
                                             final int quantity) throws SQLException {
        // 参数校验在方法入口完成
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("扣减数量必须大于0，当前值：" + quantity);
        }
        if (conn == null) {
            throw new IllegalArgumentException("数据库连接不能为null");
        }

        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(SQL_UPDATE_STOCK_WITH_CHECK);
            ps.setInt(1, quantity);
            ps.setLong(2, productId);
            ps.setInt(3, quantity);
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * 静默关闭多个 AutoCloseable 资源。
     *
     * <p>忽略关闭过程中的异常，避免影响主流程。</p>
     *
     * @param resources 待关闭的资源数组，允许 null 元素
     */
    private void closeQuietly(final AutoCloseable... resources) {
        for (final AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (final Exception ignored) {
                    // 静默关闭，不抛出异常
                }
            }
        }
    }
}
