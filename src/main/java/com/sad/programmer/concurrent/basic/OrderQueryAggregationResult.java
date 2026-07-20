package com.sad.programmer.concurrent.basic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单聚合查询结果。
 *
 * <p>该对象用于承载多个下游查询的聚合结果，例如订单基础信息、库存状态、支付状态等。</p>
 */
public class OrderQueryAggregationResult {

    /**
     * 是否所有下游任务都在超时时间内完成。
     */
    private final boolean completed;

    /**
     * 下游名称到查询结果的映射。
     *
     * <p>使用不可变视图暴露，避免调用方修改聚合服务内部状态。</p>
     */
    private final Map<String, String> values;

    /**
     * 创建订单聚合查询结果。
     *
     * @param completed 是否全部完成
     * @param values 下游结果
     */
    public OrderQueryAggregationResult(boolean completed, Map<String, String> values) {
        this.completed = completed;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    /**
     * 返回是否全部完成。
     *
     * @return true 表示全部下游在超时时间内完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 返回指定下游结果。
     *
     * @param name 下游名称
     * @return 查询结果，不存在时返回 null
     */
    public String getValue(String name) {
        return values.get(name);
    }

    /**
     * 返回全部下游结果。
     *
     * @return 下游结果不可变视图
     */
    public Map<String, String> getValues() {
        return values;
    }
}
