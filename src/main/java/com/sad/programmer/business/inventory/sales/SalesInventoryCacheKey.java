package com.sad.programmer.business.inventory.sales;

/**
 * 销售层库存 Redis Key 常量工具类。
 *
 * <p>集中管理销售库存模块使用的所有 Redis Key 前缀和生成方法，
 * 避免 Key 拼接逻辑散落在业务代码中，降低出错风险。</p>
 *
 * <p>Key 命名规范：
 * <ul>
 *   <li>销售库存：{@code inventory:sales:{productId}}</li>
 *   <li>分布式锁：{@code inventory:sales:lock:{productId}}</li>
 * </ul></p>
 *
 * @author sad-programmer
 */
public final class SalesInventoryCacheKey {

    /**
     * 销售库存 Key 前缀。
     */
    private static final String SALES_STOCK_PREFIX = "inventory:sales:";

    /**
     * 分布式锁 Key 前缀。
     */
    private static final String SALES_LOCK_PREFIX = "inventory:sales:lock:";

    /**
     * 私有构造方法，防止工具类被实例化。
     *
     * @throws UnsupportedOperationException 始终抛出
     */
    private SalesInventoryCacheKey() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 生成销售库存 Redis Key。
     *
     * <p>格式：{@code inventory:sales:{productId}}</p>
     *
     * @param productId 商品ID，必须大于 0
     * @return 销售库存 Key
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     */
    public static String salesStockKey(final long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        return SALES_STOCK_PREFIX + productId;
    }

    /**
     * 生成分布式锁 Redis Key。
     *
     * <p>格式：{@code inventory:sales:lock:{productId}}</p>
     *
     * @param productId 商品ID，必须大于 0
     * @return 分布式锁 Key
     * @throws IllegalArgumentException 当 productId 小于等于 0 时抛出
     */
    public static String salesStockLockKey(final long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("商品ID必须大于0，当前值：" + productId);
        }
        return SALES_LOCK_PREFIX + productId;
    }
}
