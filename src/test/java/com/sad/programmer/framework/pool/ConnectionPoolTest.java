package com.sad.programmer.framework.pool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 连接池测试。
 *
 * <p>验证连接池的核心能力：
 * <ul>
 *   <li>池初始化：minIdle 个连接预先创建</li>
 *   <li>借出与归还：连接复用</li>
 *   <li>并发安全：多线程同时借还</li>
 *   <li>池耗尽等待：超时抛异常</li>
 *   <li>池关闭：所有连接被释放</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class ConnectionPoolTest {

    /** 连接池实例。 */
    private MiniConnectionPool pool;

    /** 连接创建计数器。 */
    private final AtomicInteger createCount = new AtomicInteger(0);

    /**
     * 测试前置：创建连接池。
     */
    @Before
    public void setUp() {
        MiniConnectionPoolConfig config = new MiniConnectionPoolConfig();
        config.setMinIdle(3);
        config.setMaxActive(5);
        config.setMaxWaitMillis(1000);

        MiniConnectionPool.ConnectionFactory factory = new MiniConnectionPool.ConnectionFactory() {
            @Override
            public Connection getConnection() {
                createCount.incrementAndGet();
                return new MockConnection();
            }
        };

        pool = new MiniConnectionPool(config, factory);
    }

    /**
     * 测试后置：关闭连接池。
     */
    @After
    public void tearDown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    // ========== 池初始化测试 ==========

    /**
     * 验证池启动时创建 minIdle 个连接。
     */
    @Test
    public void shouldCreateMinIdleConnectionsOnInit() {
        assertEquals("启动时应创建 3 个连接", 3, pool.getIdleCount());
        assertEquals("总连接数应为 3", 3, pool.getTotalCount());
        assertEquals("活跃连接数应为 0", 0, pool.getActiveCount());
    }

    // ========== 借出与归还测试 ==========

    /**
     * 验证借出连接后空闲数减少。
     */
    @Test
    public void shouldDecreaseIdleCountWhenBorrowConnection() throws Exception {
        Connection conn = pool.getConnection();
        assertEquals("借出后空闲数应为 2", 2, pool.getIdleCount());
        assertEquals("借出后活跃数应为 1", 1, pool.getActiveCount());

        conn.close(); // 归还
        assertEquals("归还后空闲数应为 3", 3, pool.getIdleCount());
        assertEquals("归还后活跃数应为 0", 0, pool.getActiveCount());
    }

    /**
     * 验证归还的连接可以被复用（不创建新连接）。
     */
    @Test
    public void shouldReuseReturnedConnection() throws Exception {
        int beforeCreate = createCount.get();

        Connection conn1 = pool.getConnection();
        conn1.close(); // 归还

        Connection conn2 = pool.getConnection();
        conn2.close(); // 归还

        assertEquals("复用连接不应创建新连接", beforeCreate, createCount.get());
    }

    /**
     * 验证池化连接的 close() 是归还而非真正关闭。
     */
    @Test
    public void shouldNotCloseRealConnectionOnPooledClose() throws Exception {
        Connection conn = pool.getConnection();
        assertFalse("借出的连接不应是 closed 状态", conn.isClosed());

        conn.close(); // 归还
        assertTrue("归还后 isClosed 应返回 true", conn.isClosed());

        // 再次获取，应该是同一个底层连接
        Connection conn2 = pool.getConnection();
        assertFalse("重新借出的连接不应是 closed 状态", conn2.isClosed());
        conn2.close();
    }

    // ========== 动态扩池测试 ==========

    /**
     * 验证空闲连接不足时创建新连接（不超过 maxActive）。
     */
    @Test
    public void shouldCreateNewConnectionWhenIdleEmpty() throws Exception {
        // 借出所有空闲连接
        Connection c1 = pool.getConnection();
        Connection c2 = pool.getConnection();
        Connection c3 = pool.getConnection();

        assertEquals("空闲连接应为 0", 0, pool.getIdleCount());
        assertEquals("总连接应为 3", 3, pool.getTotalCount());

        // 再借一个，应该创建新连接
        Connection c4 = pool.getConnection();
        assertEquals("总连接应为 4", 4, pool.getTotalCount());

        c1.close();
        c2.close();
        c3.close();
        c4.close();
    }

    // ========== 池耗尽等待测试 ==========

    /**
     * 验证达到 maxActive 后新请求阻塞等待。
     */
    @Test
    public void shouldBlockWhenPoolExhausted() throws Exception {
        // 借出所有连接（maxActive = 5）
        List<Connection> borrowed = new ArrayList<Connection>();
        for (int i = 0; i < 5; i++) {
            borrowed.add(pool.getConnection());
        }

        assertEquals("活跃连接应为 5", 5, pool.getActiveCount());

        final AtomicInteger waitResult = new AtomicInteger(0);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        // 另一个线程尝试获取连接，应该阻塞
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    pool.getConnection();
                    waitResult.set(1); // 获取成功
                } catch (RuntimeException e) {
                    waitResult.set(-1); // 超时
                } finally {
                    done.countDown();
                }
            }
        });
        waiter.start();
        started.await();

        // 等待一小段时间，确保 waiter 线程在阻塞中
        Thread.sleep(200);
        assertEquals("等待线程应仍在阻塞", 0, waitResult.get());

        // 归还一个连接，waiter 应该能获取到
        borrowed.get(0).close();
        done.await(2, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("归还后等待线程应获取成功", 1, waitResult.get());

        // 清理
        for (int i = 1; i < borrowed.size(); i++) {
            borrowed.get(i).close();
        }
    }

    /**
     * 验证等待超时后抛出异常。
     */
    @Test
    public void shouldThrowExceptionWhenWaitTimeout() throws Exception {
        // 借出所有连接
        List<Connection> borrowed = new ArrayList<Connection>();
        for (int i = 0; i < 5; i++) {
            borrowed.add(pool.getConnection());
        }

        // 尝试获取连接，应该超时
        try {
            pool.getConnection();
            fail("池耗尽时应抛出异常");
        } catch (RuntimeException e) {
            assertTrue("异常应包含超时信息", e.getMessage().contains("超时"));
        }

        // 清理
        for (Connection conn : borrowed) {
            conn.close();
        }
    }

    // ========== 池关闭测试 ==========

    /**
     * 验证关闭池后所有连接被释放。
     */
    @Test
    public void shouldCloseAllConnectionsWhenPoolClosed() {
        pool.close();

        assertTrue("池应已关闭", pool.isClosed());
        assertEquals("关闭后空闲连接应为 0", 0, pool.getIdleCount());
        assertEquals("关闭后总连接应为 0", 0, pool.getTotalCount());
    }

    /**
     * 验证关闭池后无法获取连接。
     */
    @Test
    public void shouldThrowExceptionWhenGetConnectionAfterClosed() {
        pool.close();

        try {
            pool.getConnection();
            fail("关闭后获取连接应抛异常");
        } catch (RuntimeException e) {
            assertTrue("异常应包含已关闭信息", e.getMessage().contains("已关闭"));
        }
    }

    // ========== 并发安全测试 ==========

    /**
     * 验证多线程并发借还不抛异常。
     */
    @Test
    public void shouldHandleConcurrentBorrowAndReturn() throws Exception {
        final int threadCount = 10;
        final int opsPerThread = 50;
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int j = 0; j < opsPerThread; j++) {
                            Connection conn = null;
                            try {
                                conn = pool.getConnection();
                                // 模拟使用
                                Thread.sleep(1);
                                successCount.incrementAndGet();
                            } finally {
                                if (conn != null) {
                                    conn.close();
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }

        latch.await();

        assertEquals("不应有错误", 0, errorCount.get());
        assertEquals("应全部成功", threadCount * opsPerThread, successCount.get());
        assertEquals("并发结束后活跃连接应为 0", 0, pool.getActiveCount());
    }
}
