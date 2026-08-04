package com.sad.programmer.framework.spring.transaction;

import com.sad.programmer.framework.spring.annotation.MiniComponent;

/**
 * 账户服务实现，类级别标注 @MiniTransactional。
 *
 * <p>所有 public 方法默认在事务中执行。
 * queryBalance 方法虽然是 public 但不修改数据，仍会开启事务（和 Spring 默认行为一致）。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
@MiniTransactional
public class AccountService implements IAccountService {

    /** 操作计数器（用于验证方法确实被执行）。 */
    private int operationCount = 0;

    @Override
    public void transfer(String from, String to, long amount) {
        operationCount++;
        // 模拟：扣减转出方余额
        // 模拟：增加转入方余额
        // 两个操作在同一事务中，要么都成功，要么都回滚
    }

    @Override
    public void transferWithRollback(String from, String to, long amount) {
        operationCount++;
        // 模拟：扣减转出方余额（成功）
        // 模拟：增加转入方余额（失败）
        throw new RuntimeException("余额不足，转账失败");
    }

    @Override
    public String queryBalance(String account) {
        operationCount++;
        return account + " 余额: 1000";
    }

    /**
     * 获取操作计数。
     *
     * @return 操作计数
     */
    public int getOperationCount() {
        return operationCount;
    }
}
