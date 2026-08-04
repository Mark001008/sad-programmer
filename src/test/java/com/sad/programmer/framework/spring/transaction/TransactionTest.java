package com.sad.programmer.framework.spring.transaction;

import com.sad.programmer.framework.spring.DefaultBeanFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 声明式事务测试。
 *
 * <p>验证 @MiniTransactional 的核心能力：
 * <ul>
 *   <li>事务开启与提交（成功路径）</li>
 *   <li>事务回滚（异常路径）</li>
 *   <li>ThreadLocal 连接绑定与释放</li>
 *   <li>同一事务共享连接</li>
 *   <li>事务隔离：两次独立事务使用不同连接</li>
 *   <li>并发场景：不同线程的事务互不干扰</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class TransactionTest {

    /** IoC 容器。 */
    private DefaultBeanFactory factory;

    /** 事务管理器。 */
    private TransactionManager transactionManager;

    /** 连接创建计数器。 */
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    /**
     * 测试前置：初始化容器和事务管理器。
     */
    @Before
    public void setUp() {
        // 创建 Mock 连接工厂，统计连接创建次数
        TransactionManager.ConnectionFactory mockFactory = new TransactionManager.ConnectionFactory() {
            @Override
            public java.sql.Connection getConnection() {
                connectionCount.incrementAndGet();
                return new MockConnection();
            }
        };

        transactionManager = new TransactionManager(mockFactory);
        connectionCount.set(0);

        factory = new DefaultBeanFactory();
        factory.loadProperties();

        // 注册 Bean 和事务后处理器
        factory.registerBeanDefinition(AccountService.class);
        factory.addBeanPostProcessor(new TransactionBeanPostProcessor(transactionManager));
    }

    /**
     * 测试后置：关闭容器。
     */
    @After
    public void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    // ========== 核心测试 ==========

    /**
     * 验证成功路径：事务正常提交，连接正确释放。
     */
    @Test
    public void shouldCommitTransactionWhenMethodSucceeds() {
        IAccountService service = (IAccountService) factory.getBean("accountService");

        // 执行事务方法（不应抛异常说明提交成功）
        service.transfer("Alice", "Bob", 100);

        // 验证：事务结束后 ThreadLocal 已清除
        assertNull("提交后 ThreadLocal 应已清除", transactionManager.getCurrentConnection());

        // 验证：创建了 1 个连接
        assertEquals("应创建 1 个连接", 1, connectionCount.get());
    }

    /**
     * 验证异常路径：事务回滚，连接正确释放。
     */
    @Test
    public void shouldRollbackTransactionWhenMethodThrows() {
        IAccountService service = (IAccountService) factory.getBean("accountService");
        try {
            service.transferWithRollback("Alice", "Bob", 100);
            fail("应抛出 RuntimeException");
        } catch (RuntimeException e) {
            assertTrue("异常应包含余额不足", e.getMessage().contains("余额不足"));
        }

        // 验证：回滚后 ThreadLocal 已清除
        assertNull("回滚后 ThreadLocal 应已清除", transactionManager.getCurrentConnection());
    }

    /**
     * 验证 ThreadLocal 绑定：事务期间有连接，事务结束后无连接。
     */
    @Test
    public void shouldBindAndUnbindConnectionViaThreadLocal() {
        IAccountService service = (IAccountService) factory.getBean("accountService");

        // 事务前
        assertNull("事务开始前应无连接", transactionManager.getCurrentConnection());

        // 执行事务方法
        service.transfer("Alice", "Bob", 100);

        // 事务后
        assertNull("事务结束后应无连接", transactionManager.getCurrentConnection());
    }

    /**
     * 验证事务隔离：两次独立事务使用不同的连接。
     */
    @Test
    public void shouldUseDifferentConnectionsForSeparateTransactions() {
        IAccountService service = (IAccountService) factory.getBean("accountService");

        // 第一次事务
        service.transfer("Alice", "Bob", 100);
        int countAfterFirst = connectionCount.get();

        // 第二次事务
        service.transfer("Bob", "Charlie", 50);
        int countAfterSecond = connectionCount.get();

        assertEquals("两次事务应各创建 1 个连接", 2, countAfterSecond);
        assertEquals("第一次事务后应创建 1 个连接", 1, countAfterFirst);
    }

    /**
     * 验证并发场景：不同线程的事务互不干扰。
     *
     * <p>ThreadLocal 的核心价值：每个线程独立的连接，互不影响。</p>
     */
    @Test
    public void shouldIsolateTransactionBetweenThreads() throws InterruptedException {
        final IAccountService service = (IAccountService) factory.getBean("accountService");
        final AtomicInteger successCount = new AtomicInteger(0);

        // 3 个线程同时执行事务
        Thread[] threads = new Thread[3];
        for (int i = 0; i < threads.length; i++) {
            final String from = "User" + i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        service.transfer(from, "Target", 100);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 不应有异常
                    }
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals("3 个线程应全部成功", 3, successCount.get());
        assertEquals("应创建 3 个独立连接", 3, connectionCount.get());

        // 所有线程结束后，主线程的 ThreadLocal 应为空
        assertNull("并发事务结束后 ThreadLocal 应为空", transactionManager.getCurrentConnection());
    }

    /**
     * 验证非事务方法不开启事务。
     */
    @Test
    public void shouldNotStartTransactionForNonTransactionalMethod() {
        factory.registerBeanDefinition(QueryService.class);
        IQueryService service = (IQueryService) factory.getBean("queryService");

        // 非事务方法
        String result = service.query("test");
        assertNotNull("查询应返回结果", result);
        assertNull("非事务方法不应创建连接", transactionManager.getCurrentConnection());

        // 不应创建任何连接
        assertEquals("非事务方法不应创建连接", 0, connectionCount.get());
    }
}
