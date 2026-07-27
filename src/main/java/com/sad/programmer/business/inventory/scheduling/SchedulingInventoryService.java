package com.sad.programmer.business.inventory.scheduling;

/**
 * 调度层库存服务接口。
 *
 * <p>定义库存预占的完整生命周期操作：预占 → 锁定 → 确认扣减（或解锁/回滚）。
 * 调度层位于仓储层之上，负责在订单、支付等业务流程中协调库存的占用与释放，
 * 保证库存操作的事务一致性和幂等性。</p>
 *
 * <p>状态流转规则：
 * <ul>
 *   <li>RESERVED(0) → LOCKED(1)：支付阶段锁定</li>
 *   <li>RESERVED(0) → UNLOCKED(2)：主动解锁或过期</li>
 *   <li>LOCKED(1) → CONFIRMED(3)：支付成功确认扣减</li>
 *   <li>LOCKED(1) → UNLOCKED(2)：支付失败解锁</li>
 *   <li>已确认(CONFIRMED)不可回滚</li>
 * </ul></p>
 */
public interface SchedulingInventoryService {

    /**
     * 预占库存。
     *
     * <p>在用户下单时调用，为指定商品预占一定数量的库存。
     * 预占成功后进入 RESERVED 状态，超过过期时间将自动失效。
     * 同一订单对同一商品的重复预占请求应具有幂等性。</p>
     *
     * @param orderId      订单ID，不能为空
     * @param productId    商品ID，必须大于 0
     * @param quantity     预占数量，必须大于 0
     * @param expireMillis 预占过期时间（毫秒），必须大于 0
     * @return 预占结果，包含是否成功、预占ID或失败消息
     * @throws IllegalArgumentException 当参数不合法时
     */
    ReservationResult reserveStock(String orderId, long productId, int quantity, long expireMillis);

    /**
     * 锁定预占库存。
     *
     * <p>在支付发起阶段调用，将已预占的库存从 RESERVED 状态升级为 LOCKED 状态，
     * 表示用户已发起支付，库存应被严格锁定不可释放。如果预占已过期，
     * 则自动标记为 UNLOCKED 并返回 false。</p>
     *
     * @param reservationId 预占ID，不能为空
     * @param paymentId     支付流水ID，不能为空
     * @return true 表示锁定成功，false 表示锁定失败（记录不存在、状态不符或已过期）
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    boolean lockStock(String reservationId, String paymentId);

    /**
     * 解锁预占库存。
     *
     * <p>在支付取消、超时或主动释放场景调用，将处于 RESERVED 或 LOCKED 状态的
     * 预占记录标记为 UNLOCKED，释放对应库存。已确认扣减(CONFIRMED)的记录不可解锁。</p>
     *
     * @param reservationId 预占ID，不能为空
     * @param reason        解锁原因，不能为空，用于审计追踪
     * @return true 表示解锁成功，false 表示解锁失败（记录不存在或状态不符）
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    boolean unlockStock(String reservationId, String reason);

    /**
     * 确认扣减库存。
     *
     * <p>在支付成功回调阶段调用，将处于 LOCKED 状态的预占记录标记为 CONFIRMED，
     * 表示库存扣减已最终确认，不可再回滚或解锁。只有 LOCKED 状态的记录才能确认扣减。</p>
     *
     * @param reservationId 预占ID，不能为空
     * @return true 表示确认成功，false 表示确认失败（记录不存在或状态不是 LOCKED）
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    boolean confirmDeduction(String reservationId);

    /**
     * 回滚预占库存。
     *
     * <p>在业务异常或补偿场景调用，将非 CONFIRMED 状态的预占记录标记为 UNLOCKED，
     * 恢复库存可用。已确认扣减(CONFIRMED)的记录不可回滚，以保证扣减的最终一致性。</p>
     *
     * @param reservationId 预占ID，不能为空
     * @param reason        回滚原因，不能为空，用于审计追踪
     * @return true 表示回滚成功，false 表示回滚失败（记录不存在或已是 CONFIRMED）
     * @throws IllegalArgumentException 当参数为 null 或空字符串时
     */
    boolean rollbackStock(String reservationId, String reason);
}
