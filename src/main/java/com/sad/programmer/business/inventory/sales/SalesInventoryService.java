package com.sad.programmer.business.inventory.sales;

import java.util.List;

/**
 * 销售层库存服务接口。
 *
 * <p>定义销售层对可销售库存的查询、分配、回收和同步操作。
 * 销售层面向销售渠道，管理各渠道的可销售库存分配，
 * 确保订单创建时能准确扣减、取消时能正确回收库存。</p>
 */
public interface SalesInventoryService {

    /**
     * 查询指定商品在指定渠道的可销售库存。
     *
     * <p>根据商品ID和渠道名称查询该渠道下的可销售库存数量，
     * 同时返回该商品所有渠道的库存分布信息。若商品无库存记录，
     * 返回可用库存为 0 的空结果。</p>
     *
     * @param productId 商品ID，必须大于 0
     * @param channel   渠道名称，不能为 null
     * @return 可销售库存查询结果，包含商品ID、总可用库存和渠道库存分布
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     * @throws IllegalArgumentException 当 channel 为 null 或空字符串时抛出
     */
    SalesInventoryResult queryAvailableStock(long productId, String channel);

    /**
     * 批量查询指定商品列表在指定渠道的可销售库存。
     *
     * <p>对每个商品ID执行单次查询逻辑，返回结果列表与输入列表顺序一致。
     * 若某个商品无库存记录，该商品对应的结果为可用库存 0 的空结果。</p>
     *
     * @param productIds 商品ID列表，不能为 null 或空列表
     * @param channel    渠道名称，不能为 null
     * @return 可销售库存查询结果列表，与输入顺序一致
     * @throws IllegalArgumentException 当 productIds 为 null 或空列表时抛出
     * @throws IllegalArgumentException 当 channel 为 null 或空字符串时抛出
     */
    List<SalesInventoryResult> batchQueryAvailableStock(List<Long> productIds, String channel);

    /**
     * 为指定订单分配商品在指定渠道的可销售库存。
     *
     * <p>在订单创建时调用，校验参数合法性后，查找或创建商品的库存条目，
     * 检查该渠道的可销售库存是否充足（≥ quantity），充足则扣减渠道库存
     * 和总可用库存并返回 true，库存不足则返回 false。若 channel 为 null
     * 或空字符串，默认使用 "DEFAULT" 作为渠道名。</p>
     *
     * @param orderId   订单号，不能为 null 或空字符串
     * @param productId 商品ID，必须大于 0
     * @param quantity  分配数量，必须大于 0
     * @param channel   渠道名称，为 null 或空时使用 "DEFAULT"
     * @return true 表示分配成功，false 表示渠道库存不足
     * @throws IllegalArgumentException 当 orderId 为 null 或空字符串时抛出
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     * @throws IllegalArgumentException 当 quantity 小于等于 0 时抛出
     */
    boolean allocateStock(String orderId, long productId, int quantity, String channel);

    /**
     * 回收指定订单在指定渠道的可销售库存。
     *
     * <p>在订单取消或超时时调用，校验参数合法性后，查找商品的库存条目，
     * 增加该渠道的库存和总可用库存并返回 true。若商品无库存记录则返回 false。
     * 若 channel 为 null 或空字符串，默认使用 "DEFAULT" 作为渠道名。</p>
     *
     * @param orderId   订单号，不能为 null 或空字符串
     * @param productId 商品ID，必须大于 0
     * @param quantity  回收数量，必须大于 0
     * @param channel   渠道名称，为 null 或空时使用 "DEFAULT"
     * @return true 表示回收成功，false 表示商品无库存记录
     * @throws IllegalArgumentException 当 orderId 为 null 或空字符串时抛出
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     * @throws IllegalArgumentException 当 quantity 小于等于 0 时抛出
     */
    boolean reclaimStock(String orderId, long productId, int quantity, String channel);

    /**
     * 同步商品的实际库存到销售层。
     *
     * <p>由仓储层或调度层调用，将商品的实际可用库存数量同步到销售层。
     * 查找或创建商品的库存条目，直接设置总可用库存为指定数量。
     * 此操作会重置该商品的总可用库存，但不影响各渠道的库存分布。</p>
     *
     * @param productId 商品ID，必须大于 0
     * @param quantity  实际库存数量，不能为负数
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     * @throws IllegalArgumentException 当 quantity 为负数时抛出
     */
    void syncActualStock(long productId, int quantity);
}
