package com.sad.programmer.business.inventory.warehouse;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 仓库库存数据访问对象实现类。
 *
 * <p>使用 {@link PreparedStatement} 对 {@code warehouse_inventory} 表执行原生 SQL。
 * 所有方法接收外部传入的 {@link Connection}，由调用方负责事务提交/回滚和连接关闭。
 * 更新类方法利用 WHERE 条件实现乐观并发控制，避免在 DAO 层加应用锁。</p>
 */
public class WarehouseInventoryDaoImpl implements WarehouseInventoryDao {

    /**
     * 根据仓库 ID 和商品 ID 查询库存记录（带行锁）。
     *
     * <p>使用 {@code SELECT ... FOR UPDATE} 对目标行加排他锁，
     * 必须在已开启事务的连接上调用才能生效。记录不存在时返回 null。</p>
     *
     * @param conn        数据库连接，必须已开启事务
     * @param warehouseId 仓库 ID
     * @param productId   商品 ID
     * @return 库存查询结果，不存在时返回 null
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    @Override
    public WarehouseInventoryResult findByWarehouseAndProduct(final Connection conn,
                                                              final long warehouseId,
                                                              final long productId) throws SQLException {
        String sql = "SELECT warehouse_id, product_id, total_stock, available_stock, locked_stock "
                + "FROM warehouse_inventory WHERE warehouse_id = ? AND product_id = ? FOR UPDATE";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setLong(1, warehouseId);
            ps.setLong(2, productId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return new WarehouseInventoryResult(
                        rs.getLong("warehouse_id"),
                        rs.getLong("product_id"),
                        rs.getInt("total_stock"),
                        rs.getInt("available_stock"),
                        rs.getInt("locked_stock"));
            }
            return null;
        } finally {
            JdbcUtil.close(rs, ps);
        }
    }

    /**
     * 插入一条新的库存记录。
     *
     * <p>向表中插入仓库-商品维度的库存行，锁定库存默认为 0。
     * 若仓库-商品组合已存在，将因唯一约束冲突抛出 {@link SQLException}。</p>
     *
     * @param conn           数据库连接
     * @param warehouseId    仓库 ID
     * @param productId      商品 ID
     * @param totalStock     初始总库存
     * @param availableStock 初始可用库存
     * @return 影响行数，正常为 1
     * @throws SQLException 当 SQL 执行失败或唯一约束冲突时抛出
     */
    @Override
    public int insert(final Connection conn, final long warehouseId, final long productId,
                      final int totalStock, final int availableStock) throws SQLException {
        String sql = "INSERT INTO warehouse_inventory "
                + "(warehouse_id, product_id, total_stock, available_stock, locked_stock) "
                + "VALUES (?, ?, ?, ?, 0)";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setLong(1, warehouseId);
            ps.setLong(2, productId);
            ps.setInt(3, totalStock);
            ps.setInt(4, availableStock);
            return ps.executeUpdate();
        } finally {
            JdbcUtil.close(ps);
        }
    }

    /**
     * 按增量更新库存的总库存、可用库存和锁定库存。
     *
     * <p>三个增量参数分别作用于对应的库存字段，可为正数（增加）或负数（减少）。
     * 记录不存在时影响行数为 0。</p>
     *
     * @param conn           数据库连接
     * @param warehouseId    仓库 ID
     * @param productId      商品 ID
     * @param totalDelta     总库存增量
     * @param availableDelta 可用库存增量
     * @param lockedDelta    锁定库存增量
     * @return 影响行数，0 表示记录不存在
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    @Override
    public int updateStock(final Connection conn, final long warehouseId, final long productId,
                           final int totalDelta, final int availableDelta,
                           final int lockedDelta) throws SQLException {
        String sql = "UPDATE warehouse_inventory "
                + "SET total_stock = total_stock + ?, "
                + "    available_stock = available_stock + ?, "
                + "    locked_stock = locked_stock + ? "
                + "WHERE warehouse_id = ? AND product_id = ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, totalDelta);
            ps.setInt(2, availableDelta);
            ps.setInt(3, lockedDelta);
            ps.setLong(4, warehouseId);
            ps.setLong(5, productId);
            return ps.executeUpdate();
        } finally {
            JdbcUtil.close(ps);
        }
    }

    /**
     * 锁定库存：将可用库存转为锁定库存（乐观更新）。
     *
     * <p>通过 WHERE 条件 {@code available_stock >= qty} 保证只有在可用库存充足时才执行更新。
     * 若可用库存不足，影响行数为 0，调用方可据此判断操作是否成功。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID
     * @param productId   商品 ID
     * @param quantity    锁定数量
     * @return 影响行数，0 表示可用库存不足
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    @Override
    public int updateStockWithLock(final Connection conn, final long warehouseId,
                                   final long productId, final int quantity) throws SQLException {
        String sql = "UPDATE warehouse_inventory "
                + "SET available_stock = available_stock - ?, "
                + "    locked_stock = locked_stock + ? "
                + "WHERE warehouse_id = ? AND product_id = ? AND available_stock >= ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, quantity);
            ps.setInt(2, quantity);
            ps.setLong(3, warehouseId);
            ps.setLong(4, productId);
            ps.setInt(5, quantity);
            return ps.executeUpdate();
        } finally {
            JdbcUtil.close(ps);
        }
    }

    /**
     * 解锁库存：将锁定库存释放回可用库存。
     *
     * <p>锁定库存减少、可用库存增加，不改变总库存。
     * 若记录不存在则影响行数为 0。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID
     * @param productId   商品 ID
     * @param quantity    解锁数量
     * @return 影响行数，0 表示记录不存在
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    @Override
    public int updateStockUnlock(final Connection conn, final long warehouseId,
                                 final long productId, final int quantity) throws SQLException {
        String sql = "UPDATE warehouse_inventory "
                + "SET locked_stock = locked_stock - ?, "
                + "    available_stock = available_stock + ? "
                + "WHERE warehouse_id = ? AND product_id = ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, quantity);
            ps.setInt(2, quantity);
            ps.setLong(3, warehouseId);
            ps.setLong(4, productId);
            return ps.executeUpdate();
        } finally {
            JdbcUtil.close(ps);
        }
    }

    /**
     * 出库扣减：同时减少总库存和可用库存（乐观更新）。
     *
     * <p>通过 WHERE 条件 {@code available_stock >= qty} 保证只有在可用库存充足时才执行扣减。
     * 若可用库存不足，影响行数为 0，调用方可据此判断出库是否成功。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID
     * @param productId   商品 ID
     * @param quantity    出库数量
     * @return 影响行数，0 表示可用库存不足
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    @Override
    public int updateStockOutbound(final Connection conn, final long warehouseId,
                                   final long productId, final int quantity) throws SQLException {
        String sql = "UPDATE warehouse_inventory "
                + "SET total_stock = total_stock - ?, "
                + "    available_stock = available_stock - ? "
                + "WHERE warehouse_id = ? AND product_id = ? AND available_stock >= ?";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setInt(1, quantity);
            ps.setInt(2, quantity);
            ps.setLong(3, warehouseId);
            ps.setLong(4, productId);
            ps.setInt(5, quantity);
            return ps.executeUpdate();
        } finally {
            JdbcUtil.close(ps);
        }
    }
}
