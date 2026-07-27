package com.sad.programmer.business.inventory.warehouse;

/**
 * 仓库库存查询结果类。
 *
 * <p>封装某个仓库中某个商品的库存快照信息，包括总库存、可用库存和锁定库存。
 * 所有字段均为 final，实例创建后不可变。满足关系：totalStock = availableStock + lockedStock。</p>
 */
public final class WarehouseInventoryResult {

    /**
     * 仓库 ID。
     */
    private final long warehouseId;

    /**
     * 商品 ID。
     */
    private final long productId;

    /**
     * 总库存数量。
     */
    private final int totalStock;

    /**
     * 可用库存数量，即未被锁定、可用于出库的库存。
     */
    private final int availableStock;

    /**
     * 锁定库存数量，即已被订单预占但尚未实际出库的库存。
     */
    private final int lockedStock;

    /**
     * 构造仓库库存查询结果。
     *
     * @param warehouseId    仓库 ID
     * @param productId      商品 ID
     * @param totalStock     总库存数量，不能为负数
     * @param availableStock 可用库存数量，不能为负数
     * @param lockedStock    锁定库存数量，不能为负数
     * @throws IllegalArgumentException 当任何库存字段为负数时抛出
     */
    public WarehouseInventoryResult(final long warehouseId, final long productId,
                                    final int totalStock, final int availableStock,
                                    final int lockedStock) {
        if (totalStock < 0) {
            throw new IllegalArgumentException("总库存不能为负数: " + totalStock);
        }
        if (availableStock < 0) {
            throw new IllegalArgumentException("可用库存不能为负数: " + availableStock);
        }
        if (lockedStock < 0) {
            throw new IllegalArgumentException("锁定库存不能为负数: " + lockedStock);
        }
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.totalStock = totalStock;
        this.availableStock = availableStock;
        this.lockedStock = lockedStock;
    }

    /**
     * 获取仓库 ID。
     *
     * @return 仓库 ID
     */
    public long getWarehouseId() {
        return warehouseId;
    }

    /**
     * 获取商品 ID。
     *
     * @return 商品 ID
     */
    public long getProductId() {
        return productId;
    }

    /**
     * 获取总库存数量。
     *
     * @return 总库存
     */
    public int getTotalStock() {
        return totalStock;
    }

    /**
     * 获取可用库存数量。
     *
     * @return 可用库存
     */
    public int getAvailableStock() {
        return availableStock;
    }

    /**
     * 获取锁定库存数量。
     *
     * @return 锁定库存
     */
    public int getLockedStock() {
        return lockedStock;
    }

    /**
     * 生成库存结果的字符串表示。
     *
     * @return 包含仓库ID、商品ID、总库存、可用库存和锁定库存的字符串
     */
    @Override
    public String toString() {
        return "WarehouseInventoryResult{"
                + "warehouseId=" + warehouseId
                + ", productId=" + productId
                + ", totalStock=" + totalStock
                + ", availableStock=" + availableStock
                + ", lockedStock=" + lockedStock
                + '}';
    }
}
