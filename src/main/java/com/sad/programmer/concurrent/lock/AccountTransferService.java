package com.sad.programmer.concurrent.lock;

import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link java.util.concurrent.locks.ReentrantLock} 的账户转账服务 Demo。
 *
 * <p>该类演示多资源加锁时的生产级习惯：固定加锁顺序 + 超时获取锁 + finally 释放锁。
 * 这样可以避免两个线程反向转账时互相持有对方需要的锁而形成死锁。</p>
 */
public class AccountTransferService {

    /**
     * 从一个账户向另一个账户转账。
     *
     * @param from 转出账户
     * @param to 转入账户
     * @param amountCents 转账金额，单位为分
     * @param timeout 获取两把锁的总超时时间
     * @param unit 超时时间单位
     * @return true 表示转账成功；false 表示超时未拿到锁，调用方可以重试或补偿
     * @throws InterruptedException 等待锁期间线程被中断
     */
    public boolean transfer(TransferAccount from,
                            TransferAccount to,
                            long amountCents,
                            long timeout,
                            TimeUnit unit) throws InterruptedException {
        if (from == null || to == null) {
            throw new IllegalArgumentException("account must not be null");
        }
        if (from == to) {
            throw new IllegalArgumentException("from and to must be different");
        }
        if (from.getAccountNo().equals(to.getAccountNo())) {
            throw new IllegalArgumentException("from and to accountNo must be different");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }

        // 使用同一个 deadline 控制“两把锁”的总等待时间，而不是每把锁各等 timeout。
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        // 固定锁顺序是避免死锁的关键：所有线程都按账户号从小到大加锁。
        TransferAccount first = from.getAccountNo().compareTo(to.getAccountNo()) < 0 ? from : to;
        TransferAccount second = first == from ? to : from;
        boolean firstLocked = false;
        boolean secondLocked = false;
        try {
            // 第一把锁拿不到，直接返回失败，避免业务线程无限等待。
            firstLocked = first.lock().tryLock(timeout, unit);
            if (!firstLocked) {
                return false;
            }

            // 第二把锁只使用剩余时间，确保 transfer 方法整体受 timeout 约束。
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            secondLocked = second.lock().tryLock(remainingNanos, TimeUnit.NANOSECONDS);
            if (!secondLocked) {
                return false;
            }

            // 两把锁都拿到后，才进入真正的余额变更临界区。
            from.decrease(amountCents);
            to.increase(amountCents);
            return true;
        } finally {
            // 解锁顺序和加锁顺序相反，且只释放已经拿到的锁。
            if (secondLocked) {
                second.lock().unlock();
            }
            if (firstLocked) {
                first.lock().unlock();
            }
        }
    }
}
