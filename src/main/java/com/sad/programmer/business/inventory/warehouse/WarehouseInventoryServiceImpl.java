package com.sad.programmer.business.inventory.warehouse;

import com.sad.programmer.database.common.JdbcUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 仓库层库存服务实现类（MySQL 投产版本）。
 *
 * <p>通过 {@link WarehouseInventoryDao} 访问 MySQL 数据库，使用 JDBC 原生事务保证数据一致性。
 * 每个写操作方法独立获取连接、开启事务、提交/回滚、关闭连接，确保连接不泄漏。
 * 使用 {@link Connection#TRANSACTION_REPEATABLE_READ} 隔离级别配合 {@code SELECT ... FOR UPDATE}
 * 行锁和乐观更新（WHERE 条件校验）实现并发安全。</p>
 */
public class WarehouseInventoryServiceImpl implements WarehouseInventoryService {

    /**
     * 仓库库存数据访问对象，负责 SQL 操作。
     */
    private final WarehouseInventoryDao dao;

    /**
     * 构造仓库层库存服务实现，使用默认的 DAO 实现。
     */
    public WarehouseInventoryServiceImpl() {
        this.dao = new WarehouseInventoryDaoImpl();
    }

    /**
     * 构造仓库层库存服务实现，注入指定的 DAO。
     *
     * @param dao 仓库库存数据访问对象，不能为 null
     * @throws IllegalArgumentException 当 dao 为 null 时抛出
     */
    public WarehouseInventoryServiceImpl(final WarehouseInventoryDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("dao 不能为 null");
        }
        this.dao = dao;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 开启事务 → 行锁查询是否存在 →
     * 存在则累加库存、不存在则插入新记录 → 提交事务 → 返回成功结果。
     * 异常时回滚事务并抛出运行时异常。</p>
     */
    @Override
    public InboundResult inbound(final long warehouseId, final long productId,
                                 final int quantity, final String batchNo,
                                 final String supplierId) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);
        validateQuantity(quantity);
        validateNotBlank(batchNo, "批次号");
        validateNotBlank(supplierId, "供应商ID");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);

            // 行锁查询，防止并发入库冲突
            WarehouseInventoryResult existing = dao.findByWarehouseAndProduct(conn, warehouseId, productId);
            if (existing != null) {
                // 已有库存记录，累加总库存和可用库存
                dao.updateStock(conn, warehouseId, productId, quantity, quantity, 0);
            } else {
                // 无记录，创建新的库存条目
                dao.insert(conn, warehouseId, productId, quantity, quantity);
            }

            conn.commit();

            // 生成入库单号
            String inboundId = "IN-" + generateShortId();
            return InboundResult.success(inboundId);
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new IllegalStateException("入库操作失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 开启事务 → 乐观更新扣减库存 →
     * 影响行数为 0 则返回失败结果 → 提交事务 → 返回成功结果。</p>
     */
    @Override
    public OutboundResult outbound(final long warehouseId, final long productId,
                                   final int quantity, final String orderId) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);
        validateQuantity(quantity);
        validateNotBlank(orderId, "订单ID");

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);

            // 乐观更新：WHERE available_stock >= qty 保证库存充足
            int affected = dao.updateStockOutbound(conn, warehouseId, productId, quantity);
            if (affected == 0) {
                // 可用库存不足，提交空事务后返回失败
                conn.commit();
                return OutboundResult.failure("可用库存不足，出库数量: " + quantity);
            }

            conn.commit();

            // 生成出库单号
            String outboundId = "OUT-" + generateShortId();
            return OutboundResult.success(outboundId);
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new IllegalStateException("出库操作失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 行锁查询 → 构建结果。
     * 记录不存在时返回全 0 的库存快照。此方法不开启事务。</p>
     */
    @Override
    public WarehouseInventoryResult queryWarehouseStock(final long warehouseId, final long productId) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            WarehouseInventoryResult result = dao.findByWarehouseAndProduct(conn, warehouseId, productId);
            if (result != null) {
                return result;
            }
            // 记录不存在，返回全 0 的库存快照
            return new WarehouseInventoryResult(warehouseId, productId, 0, 0, 0);
        } catch (SQLException e) {
            throw new IllegalStateException("查询仓库库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取单个连接 → 逐个查询每个商品的库存。
     * 某个商品无记录时对应结果为全 0。</p>
     */
    @Override
    public List<WarehouseInventoryResult> batchQueryWarehouseStock(final long warehouseId,
                                                                   final List<Long> productIds) {
        validateWarehouseId(warehouseId);
        if (productIds == null) {
            throw new IllegalArgumentException("商品ID列表不能为 null");
        }

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);

            List<WarehouseInventoryResult> results = new ArrayList<WarehouseInventoryResult>(productIds.size());
            for (Long productId : productIds) {
                if (productId == null) {
                    throw new IllegalArgumentException("商品ID列表中不能包含 null");
                }
                validateProductId(productId);

                WarehouseInventoryResult result = dao.findByWarehouseAndProduct(conn, warehouseId, productId);
                if (result != null) {
                    results.add(result);
                } else {
                    // 无记录时返回全 0 的库存快照
                    results.add(new WarehouseInventoryResult(warehouseId, productId, 0, 0, 0));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("批量查询仓库库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 开启事务 → 乐观更新锁定库存 →
     * 影响行数为 0 则抛出 IllegalStateException → 提交事务。</p>
     */
    @Override
    public void lockStock(final long warehouseId, final long productId, final int quantity) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);
        validateQuantity(quantity);

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);

            // 乐观更新：WHERE available_stock >= qty
            int affected = dao.updateStockWithLock(conn, warehouseId, productId, quantity);
            if (affected == 0) {
                throw new IllegalStateException(
                        "可用库存不足，无法锁定: 仓库=" + warehouseId
                                + " 商品=" + productId + " 需要锁定=" + quantity);
            }

            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new IllegalStateException("锁定库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 开启事务 → 更新解锁库存 → 提交事务。</p>
     */
    @Override
    public void unlockStock(final long warehouseId, final long productId, final int quantity) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);
        validateQuantity(quantity);

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);

            dao.updateStockUnlock(conn, warehouseId, productId, quantity);

            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new IllegalStateException("解锁库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现逻辑：获取连接 → 开启事务 → 行锁查询是否存在 →
     * 存在则累加库存、不存在则插入新记录 → 提交事务。</p>
     */
    @Override
    public void increaseStock(final long warehouseId, final long productId, final int quantity) {
        validateWarehouseId(warehouseId);
        validateProductId(productId);
        validateQuantity(quantity);

        Connection conn = null;
        try {
            conn = JdbcUtil.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setAutoCommit(false);

            // 行锁查询，防止并发修改冲突
            WarehouseInventoryResult existing = dao.findByWarehouseAndProduct(conn, warehouseId, productId);
            if (existing != null) {
                // 已有库存记录，累加总库存和可用库存
                dao.updateStock(conn, warehouseId, productId, quantity, quantity, 0);
            } else {
                // 无记录，创建新的库存条目
                dao.insert(conn, warehouseId, productId, quantity, quantity);
            }

            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            throw new IllegalStateException("增加库存失败: " + e.getMessage(), e);
        } finally {
            JdbcUtil.close(conn);
        }
    }

    /**
     * 静默回滚事务，忽略回滚过程中的异常。
     *
     * <p>在 catch 块中调用，确保回滚失败不会掩盖原始异常。</p>
     *
     * @param conn 数据库连接，允许为 null
     */
    private void rollbackQuietly(final Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 回滚失败不应掩盖原始异常
            }
        }
    }

    /**
     * 生成短格式唯一 ID，用于入库单号和出库单号。
     *
     * <p>取 UUID 的前 16 位十六进制字符，兼顾唯一性和可读性。</p>
     *
     * @return 16 位十六进制字符串
     */
    private String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /**
     * 校验仓库 ID 的合法性。
     *
     * @param warehouseId 仓库 ID
     * @throws IllegalArgumentException 当仓库 ID 小于等于 0 时抛出
     */
    private void validateWarehouseId(final long warehouseId) {
        if (warehouseId <= 0) {
            throw new IllegalArgumentException("仓库ID必须大于0: " + warehouseId);
        }
    }

    /**
     * 校验商品 ID 的合法性。
     *
     * @param productId 商品 ID
     * @throws IllegalArgumentException 当商品 ID 小于等于 0 时抛出
     */
    private void validateProductId(final long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0: " + productId);
        }
    }

    /**
     * 校验数量的合法性。
     *
     * @param quantity 数量
     * @throws IllegalArgumentException 当数量小于等于 0 时抛出
     */
    private void validateQuantity(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于0: " + quantity);
        }
    }

    /**
     * 校验字符串参数不为空。
     *
     * @param value      待校验的字符串
     * @param paramName  参数名称，用于错误消息
     * @throws IllegalArgumentException 当字符串为 null 或空白时抛出
     */
    private void validateNotBlank(final String value, final String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + "不能为空");
        }
    }
}
