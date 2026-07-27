package com.sad.programmer.business.inventory.warehouse;

import java.util.List;

/**
 * 仓库层库存服务接口。
 *
 * <p>定义仓库维度的库存管理操作，包括入库、出库、库存查询、
 * 锁定/解锁库存和增加库存等核心能力。</p>
 */
public interface WarehouseInventoryService {

    /**
     * 执行入库操作。
     *
     * <p>将指定数量的商品入库到目标仓库，同时记录批次号和供应商信息。
     * 若该商品在该仓库已有库存记录，则累加总库存和可用库存；
     * 若无记录，则创建新的库存条目。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    入库数量，必须大于 0
     * @param batchNo     批次号，不能为空
     * @param supplierId  供应商 ID，不能为空
     * @return 入库操作结果，包含入库单号或失败消息
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    InboundResult inbound(long warehouseId, long productId, int quantity,
                          String batchNo, String supplierId);

    /**
     * 执行出库操作。
     *
     * <p>从目标仓库扣减指定数量的商品可用库存。若可用库存不足，
     * 则出库失败并返回失败结果。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    出库数量，必须大于 0
     * @param orderId     关联的订单 ID，不能为空
     * @return 出库操作结果，包含出库单号或失败消息
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    OutboundResult outbound(long warehouseId, long productId, int quantity, String orderId);

    /**
     * 查询指定仓库中指定商品的库存信息。
     *
     * <p>返回该商品在目标仓库的库存快照，包含总库存、可用库存和锁定库存。
     * 若该商品在该仓库无库存记录，则返回全 0 的结果。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @return 库存查询结果
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    WarehouseInventoryResult queryWarehouseStock(long warehouseId, long productId);

    /**
     * 批量查询指定仓库中多个商品的库存信息。
     *
     * <p>返回每个商品在目标仓库的库存快照列表。
     * 若某个商品无库存记录，则对应结果为全 0。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productIds  商品 ID 列表，不能为空且不能包含 null
     * @return 库存查询结果列表，顺序与输入 productIds 一致
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    List<WarehouseInventoryResult> batchQueryWarehouseStock(long warehouseId, List<Long> productIds);

    /**
     * 锁定指定仓库中指定商品的库存。
     *
     * <p>将可用库存中的指定数量转为锁定库存，用于订单预占。
     * 锁定时不改变总库存，仅转移可用库存到锁定库存。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    锁定数量，必须大于 0
     * @throws IllegalArgumentException 当参数校验不通过或可用库存不足时抛出
     */
    void lockStock(long warehouseId, long productId, int quantity);

    /**
     * 解锁指定仓库中指定商品的库存。
     *
     * <p>将锁定库存中的指定数量释放回可用库存，用于订单取消等场景。
     * 解锁时不改变总库存，仅转移锁定库存到可用库存。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    解锁数量，必须大于 0
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    void unlockStock(long warehouseId, long productId, int quantity);

    /**
     * 增加指定仓库中指定商品的总库存和可用库存。
     *
     * <p>直接增加总库存和可用库存，不生成入库单号，用于调整或盘盈场景。</p>
     *
     * @param warehouseId 仓库 ID，必须大于 0
     * @param productId   商品 ID，必须大于 0
     * @param quantity    增加数量，必须大于 0
     * @throws IllegalArgumentException 当参数校验不通过时抛出
     */
    void increaseStock(long warehouseId, long productId, int quantity);
}
