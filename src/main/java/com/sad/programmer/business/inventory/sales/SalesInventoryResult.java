package com.sad.programmer.business.inventory.sales;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 可销售库存查询结果类。
 *
 * <p>封装销售层查询商品可销售库存的结果信息，包括商品ID、可销售库存总量和
 * 各渠道库存分布。所有字段均为 final，实例创建后不可变。
 * 渠道库存分布 Map 在构造时进行深拷贝，确保外部无法修改内部状态。</p>
 */
public final class SalesInventoryResult {

    /**
     * 商品ID。
     */
    private final long productId;

    /**
     * 可销售库存总量。
     */
    private final int availableStock;

    /**
     * 渠道库存分布，key 为渠道名称，value 为该渠道的可销售库存。
     * 不可变 Map，外部无法修改。
     */
    private final Map<String, Integer> channelStock;

    /**
     * 构造可销售库存查询结果。
     *
     * @param productId      商品ID，必须大于 0
     * @param availableStock 可销售库存总量，不能为负数
     * @param channelStock   渠道库存分布，不能为 null
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     * @throws IllegalArgumentException 当 availableStock 为负数时抛出
     * @throws IllegalArgumentException 当 channelStock 为 null 时抛出
     */
    public SalesInventoryResult(final long productId, final int availableStock,
                                final Map<String, Integer> channelStock) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        if (availableStock < 0) {
            throw new IllegalArgumentException("可销售库存不能为负数，当前值：" + availableStock);
        }
        if (channelStock == null) {
            throw new IllegalArgumentException("渠道库存分布不能为null");
        }
        this.productId = productId;
        this.availableStock = availableStock;
        // 深拷贝并包装为不可变Map，防止外部修改内部状态
        this.channelStock = Collections.unmodifiableMap(new HashMap<String, Integer>(channelStock));
    }

    /**
     * 创建空的可销售库存查询结果。
     *
     * <p>返回可销售库存为 0、渠道库存分布为空 Map 的实例，用于商品无库存记录时的占位。</p>
     *
     * @param productId 商品ID，必须大于 0
     * @return 可销售库存为 0 的空结果实例
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     */
    public static SalesInventoryResult empty(final long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        return new SalesInventoryResult(productId, 0, Collections.<String, Integer>emptyMap());
    }

    /**
     * 获取商品ID。
     *
     * @return 商品ID
     */
    public long getProductId() {
        return productId;
    }

    /**
     * 获取可销售库存总量。
     *
     * @return 可销售库存总量
     */
    public int getAvailableStock() {
        return availableStock;
    }

    /**
     * 获取渠道库存分布。
     *
     * @return 不可变的渠道库存分布 Map，key 为渠道名称，value 为该渠道可销售库存
     */
    public Map<String, Integer> getChannelStock() {
        return channelStock;
    }
}
