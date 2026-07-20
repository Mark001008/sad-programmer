package com.sad.programmer.concurrent.lock;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link AccountTransferService} 的并发转账测试。
 *
 * <p>该测试模拟两个线程同时做反向转账，验证固定加锁顺序可以避免死锁，并且账户总金额不丢失。</p>
 */
public class AccountTransferServiceTest {

    /**
     * 验证反向转账不会死锁，并且两笔转账都能在超时时间内完成。
     *
     * @throws Exception 测试线程等待失败或任务执行失败
     */
    @Test
    public void shouldTransferMoneyWithoutDeadlockWhenOppositeTransfersHappen() throws Exception {
        final TransferAccount accountA = new TransferAccount("A", 10000);
        final TransferAccount accountB = new TransferAccount("B", 10000);
        final AccountTransferService service = new AccountTransferService();
        final CountDownLatch startLatch = new CountDownLatch(1);

        ThreadPoolExecutor executor = newExecutor();
        try {
            Future<Boolean> aToB = executor.submit(() -> {
                // 两个转账任务同时从起跑线出发，尽量制造反向加锁竞争。
                startLatch.await();
                return service.transfer(accountA, accountB, 1000, 1, TimeUnit.SECONDS);
            });
            Future<Boolean> bToA = executor.submit(() -> {
                // 反方向转账，如果没有固定加锁顺序，容易形成 A 等 B、B 等 A 的死锁。
                startLatch.await();
                return service.transfer(accountB, accountA, 500, 1, TimeUnit.SECONDS);
            });

            // 统一放行，避免一个任务先跑完导致测试失去并发竞争意义。
            startLatch.countDown();

            assertTrue(aToB.get(2, TimeUnit.SECONDS));
            assertTrue(bToA.get(2, TimeUnit.SECONDS));
            assertEquals(9500, accountA.getBalanceCents());
            assertEquals(10500, accountB.getBalanceCents());
            // 转账只改变账户间金额分布，不应该改变系统总金额。
            assertEquals(20000, accountA.getBalanceCents() + accountB.getBalanceCents());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 创建测试线程池。
     *
     * <p>使用手动创建的 {@link ThreadPoolExecutor}，显式配置有界队列、自定义线程名和拒绝策略。</p>
     *
     * @return 测试线程池
     */
    private ThreadPoolExecutor newExecutor() {
        final AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = r -> new Thread(r, "transfer-demo-" + sequence.incrementAndGet());
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
