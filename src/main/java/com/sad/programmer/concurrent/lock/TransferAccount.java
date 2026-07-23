package com.sad.programmer.concurrent.lock;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 转账 Demo 中的账户模型。
 *
 * <p>该类用于演示单 JVM 内使用 {@link ReentrantLock} 保护账户余额更新。
 * 真实生产系统的账户余额通常应落库，并配合数据库事务、幂等流水和对账补偿。</p>
 */
public class TransferAccount {

    /**
     * 账户号。
     *
     * <p>转账服务会使用账户号做固定加锁排序，避免两个线程反向转账时出现死锁。</p>
     */
    private final String accountNo;

    /**
     * 当前账户的本地锁。
     *
     * <p>该锁只在当前 JVM 内有效，不能用于多实例之间的分布式互斥。</p>
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 账户余额，单位为分。
     *
     * <p>声明为 volatile，保证无锁读取时也能看到最新值。
     * 修改操作仍需在持有 {@link #lock} 的情况下进行。</p>
     */
    private volatile long balanceCents;

    /**
     * 创建账户。
     *
     * @param accountNo 账户号
     * @param balanceCents 初始余额，单位为分
     */
    public TransferAccount(String accountNo, long balanceCents) {
        if (accountNo == null || accountNo.trim().isEmpty()) {
            throw new IllegalArgumentException("accountNo must not be blank");
        }
        if (balanceCents < 0) {
            throw new IllegalArgumentException("balanceCents must not be negative");
        }
        this.accountNo = accountNo;
        this.balanceCents = balanceCents;
    }

    /**
     * 返回账户号。
     *
     * @return 账户号
     */
    public String getAccountNo() {
        return accountNo;
    }

    /**
     * 返回当前余额。
     *
     * <p>字段声明为 volatile，即使不在锁保护下读取也能保证可见性。
     * 测试场景通过 Future.get() 建立 happens-before 关系后读取余额。</p>
     *
     * @return 当前余额，单位为分
     */
    public long getBalanceCents() {
        return balanceCents;
    }

    /**
     * 返回账户本地锁。
     *
     * @return 当前账户锁
     */
    ReentrantLock lock() {
        return lock;
    }

    /**
     * 扣减账户余额。
     *
     * <p>调用方必须先持有账户锁。</p>
     *
     * @param amountCents 扣减金额，单位为分
     */
    void decrease(long amountCents) {
        if (balanceCents < amountCents) {
            throw new IllegalStateException("insufficient balance");
        }
        balanceCents -= amountCents;
    }

    /**
     * 增加账户余额。
     *
     * <p>调用方必须先持有账户锁。</p>
     *
     * @param amountCents 增加金额，单位为分
     */
    void increase(long amountCents) {
        balanceCents += amountCents;
    }
}
