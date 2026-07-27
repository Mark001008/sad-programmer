package com.sad.programmer.business.inventory.sales;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 销售库存数据访问接口（DAO）。
 *
 * <p>定义对 sales_inventory 表的 CRUD 操作，所有方法均接收外部传入的
 * {@link Connection}，由调用方控制事务边界和连接生命周期。</p>
 *
 * <p>表结构约定：
 * <ul>
 *   <li>product_id — 商品ID，唯一索引</li>
 *   <li>available_stock — 可用库存</li>
 *   <li>allocated_stock — 已分配库存</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public interface SalesInventoryDao {

    /**
     * 根据商品ID查询销售库存记录。
     *
     * @param conn      数据库连接，由调用方管理事务
     * @param productId 商品ID，必须大于 0
     * @return 销售库存查询结果，未找到时返回 null
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     */
    SalesInventoryResult findByProductId(Connection conn, long productId) throws SQLException;

    /**
     * 插入一条销售库存记录。
     *
     * @param conn          数据库连接，由调用方管理事务
     * @param productId     商品ID，必须大于 0
     * @param availableStock 可用库存数量，不能为负数
     * @return 受影响行数，成功插入时返回 1
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 productId 小于等于 0 或 availableStock 为负数时抛出
     */
    int insert(Connection conn, long productId, int availableStock) throws SQLException;

    /**
     * 增量更新可用库存（可正可负）。
     *
     * <p>执行 SQL：{@code UPDATE sales_inventory SET available_stock = available_stock + ? WHERE product_id = ?}</p>
     *
     * @param conn      数据库连接，由调用方管理事务
     * @param productId 商品ID，必须大于 0
     * @param delta     库存增量（正数增加，负数减少）
     * @return 受影响行数，成功更新时返回 1，记录不存在返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     */
    int updateAvailableStock(Connection conn, long productId, int delta) throws SQLException;

    /**
     * 带库存校验的扣减操作（乐观锁）。
     *
     * <p>执行 SQL：{@code UPDATE sales_inventory SET available_stock = available_stock - ? WHERE product_id = ? AND available_stock >= ?}</p>
     * <p>当可用库存不足时，WHERE 条件不匹配，返回受影响行数 0。</p>
     *
     * @param conn      数据库连接，由调用方管理事务
     * @param productId 商品ID，必须大于 0
     * @param quantity  扣减数量，必须大于 0
     * @return 受影响行数，成功扣减返回 1，库存不足返回 0
     * @throws SQLException           数据库访问异常
     * @throws IllegalArgumentException 当 productId 小于等于 0 或 quantity 小于等于 0 时抛出
     */
    int updateAvailableStockWithCheck(Connection conn, long productId, int quantity) throws SQLException;
}
