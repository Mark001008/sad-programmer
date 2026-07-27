package com.sad.programmer.business.inventory.warehouse;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 仓库库存数据访问对象接口。
 *
 * <p>定义针对 {@code warehouse_inventory} 表的 CRUD 操作。
 * 所有方法接收外部传入的 {@link Connection}，由调用方管理事务边界和连接生命周期。
 * 更新类方法返回影响行数，0 表示库存不足或记录不存在。</p>
 */
public interface WarehouseInventoryDao {

    /**
     * 根据仓库 ID 和商品 ID 查询库存记录，并使用行锁锁定该行。
     *
     * <p>执行 {@code SELECT ... FOR UPDATE}，在事务内对目标行加排他锁，
     * 防止并发事务同时修改同一条库存记录。若记录不存在则返回 null。</p>
     *
     * @param conn        数据库连接，必须已开启事务且隔离级别不低于 REPEATABLE_READ
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @return 库存查询结果，记录不存在时返回 null
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    WarehouseInventoryResult findByWarehouseAndProduct(Connection conn, long warehouseId,
                                                       long productId) throws SQLException;

    /**
     * 插入一条新的库存记录。
     *
     * <p>向 {@code warehouse_inventory} 表插入一行，锁定库存默认为 0。
     * 调用前应先确认该仓库-商品组合尚不存在记录，否则会因唯一约束冲突抛出异常。</p>
     *
     * @param conn           数据库连接
     * @param warehouseId    仓库 ID，必须大于 0
     * @param productId      商品 ID，必须大于 0
     * @param totalStock     初始总库存，不能为负数
     * @param availableStock 初始可用库存，不能为负数
     * @return 影响行数，正常情况下为 1
     * @throws SQLException 当 SQL 执行失败或唯一约束冲突时抛出
     */
    int insert(Connection conn, long warehouseId, long productId,
               int totalStock, int availableStock) throws SQLException;

    /**
     * 按增量更新库存的总库存、可用库存和锁定库存。
     *
     * <p>执行 {@code UPDATE ... SET total_stock=total_stock+?, available_stock=available_stock+?,
     * locked_stock=locked_stock+? WHERE warehouse_id=? AND product_id=?}。
     * 各增量可为正数（增加）或负数（减少）。记录不存在时影响行数为 0。</p>
     *
     * @param conn           数据库连接
     * @param warehouseId    仓库 ID，必须大于 0
     * @param productId      商品 ID，必须大于 0
     * @param totalDelta     总库存增量，正数增加、负数减少
     * @param availableDelta 可用库存增量，正数增加、负数减少
     * @param lockedDelta    锁定库存增量，正数增加、负数减少
     * @return 影响行数，0 表示记录不存在
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    int updateStock(Connection conn, long warehouseId, long productId,
                    int totalDelta, int availableDelta, int lockedDelta) throws SQLException;

    /**
     * 锁定库存：将可用库存转为锁定库存（乐观更新）。
     *
     * <p>执行 {@code UPDATE ... SET available_stock=available_stock-?,
     * locked_stock=locked_stock+? WHERE warehouse_id=? AND product_id=?
     * AND available_stock>=?}。通过 WHERE 条件中的 {@code available_stock>=qty}
     * 实现乐观并发控制，库存不足时影响行数为 0。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    锁定数量，必须大于 0
     * @return 影响行数，0 表示可用库存不足
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    int updateStockWithLock(Connection conn, long warehouseId, long productId,
                            int quantity) throws SQLException;

    /**
     * 解锁库存：将锁定库存释放回可用库存。
     *
     * <p>执行 {@code UPDATE ... SET locked_stock=locked_stock-?,
     * available_stock=available_stock+? WHERE warehouse_id=? AND product_id=?}。
     * 若记录不存在则影响行数为 0。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    解锁数量，必须大于 0
     * @return 影响行数，0 表示记录不存在
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    int updateStockUnlock(Connection conn, long warehouseId, long productId,
                          int quantity) throws SQLException;

    /**
     * 出库扣减：同时减少总库存和可用库存（乐观更新）。
     *
     * <p>执行 {@code UPDATE ... SET total_stock=total_stock-?,
     * available_stock=available_stock-? WHERE warehouse_id=? AND product_id=?
     * AND available_stock>=?}。通过 WHERE 条件中的 {@code available_stock>=qty}
     * 实现乐观并发控制，可用库存不足时影响行数为 0。</p>
     *
     * @param conn        数据库连接
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    出库数量，必须大于 0
     * @return 影响行数，0 表示可用库存不足
     * @throws SQLException 当 SQL 执行失败时抛出
     */
    int updateStockOutbound(Connection conn, long warehouseId, long productId,
                            int quantity) throws SQLException;
}
