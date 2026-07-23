package com.sad.programmer.database.lock;

import com.sad.programmer.database.common.JdbcUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * MySQL 死锁演示测试。
 *
 * <p>通过两个并发事务演示死锁的产生和预防。</p>
 */
public class DeadlockDemoTest {

    private DeadlockDemo demo;

    @Before
    public void setUp() throws Exception {
        demo = new DeadlockDemo();
        demo.initTable();
    }

    @After
    public void tearDown() throws Exception {
        demo.dropTable();
    }

    // ======================== 正常转账 ========================

    /**
     * 验证单线程正常转账。
     */
    @Test
    public void shouldTransferBetweenAccounts() throws Exception {
        Connection conn = demo.getConnection();
        try {
            long balanceA = demo.lockAndRead(conn, "A");
            long balanceB = demo.lockAndRead(conn, "B");
            demo.updateBalance(conn, "A", balanceA - 1000);
            demo.updateBalance(conn, "B", balanceB + 1000);
            conn.commit();

            assertEquals(9000, demo.readBalance(conn, "A"));
            assertEquals(11000, demo.readBalance(conn, "B"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
            JdbcUtil.close(conn);
        }
    }

    // ======================== 死锁场景 ========================

    /**
     * 验证反向加锁导致死锁。
     *
     * <p>场景：</p>
     * <ol>
     *   <li>事务 A：锁定 A → 等待锁定 B</li>
     *   <li>事务 B：锁定 B → 等待锁定 A</li>
     *   <li>形成循环等待，InnoDB 回滚其中一个事务</li>
     * </ol>
     *
     * <p>预期：其中一个事务收到 Deadlock found 异常。</p>
     */
    @Test(timeout = 15000)
    public void shouldDetectDeadlockOnReverseLockOrder() throws Exception {
        final CountDownLatch aLocked = new CountDownLatch(1);
        final CountDownLatch bLocked = new CountDownLatch(1);
        final AtomicBoolean deadlockDetected = new AtomicBoolean(false);
        final AtomicReference<Exception> deadlockException = new AtomicReference<>();

        // 事务 A：先锁 A，再锁 B
        Thread threadA = new Thread(() -> {
            Connection conn = null;
            try {
                conn = demo.getConnection();
                demo.lockAndRead(conn, "A");
                aLocked.countDown();     // 通知：A 已锁定
                bLocked.await();         // 等待 B 也锁定
                demo.lockAndRead(conn, "B");  // 尝试锁 B → 可能死锁
                conn.commit();
            } catch (SQLException e) {
                if (e.getMessage().contains("Deadlock")) {
                    deadlockDetected.set(true);
                    deadlockException.set(e);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (conn != null) {
                    try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignored) {}
                    JdbcUtil.close(conn);
                }
            }
        });

        // 事务 B：先锁 B，再锁 A
        Thread threadB = new Thread(() -> {
            Connection conn = null;
            try {
                conn = demo.getConnection();
                demo.lockAndRead(conn, "B");
                bLocked.countDown();     // 通知：B 已锁定
                aLocked.await();         // 等待 A 也锁定
                demo.lockAndRead(conn, "A");  // 尝试锁 A → 可能死锁
                conn.commit();
            } catch (SQLException e) {
                if (e.getMessage().contains("Deadlock")) {
                    deadlockDetected.set(true);
                    deadlockException.set(e);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (conn != null) {
                    try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignored) {}
                    JdbcUtil.close(conn);
                }
            }
        });

        threadA.start();
        threadB.start();
        threadA.join(10000);
        threadB.join(10000);

        assertTrue("反向加锁应触发死锁检测", deadlockDetected.get());
        assertNotNull(deadlockException.get());
    }

    // ======================== 预防：固定加锁顺序 ========================

    /**
     * 验证固定加锁顺序可以避免死锁。
     *
     * <p>所有事务都按账户号字典序加锁（A → B → C），
     * 不会出现循环等待，因此不会死锁。</p>
     */
    @Test(timeout = 10000)
    public void shouldAvoidDeadlockWithConsistentLockOrder() throws Exception {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final AtomicBoolean anyFailure = new AtomicBoolean(false);

        // 事务 A：A → B（按字典序）
        Thread threadA = new Thread(() -> {
            Connection conn = null;
            try {
                conn = demo.getConnection();
                startLatch.await();
                long a = demo.lockAndRead(conn, "A");
                long b = demo.lockAndRead(conn, "B");
                demo.updateBalance(conn, "A", a - 500);
                demo.updateBalance(conn, "B", b + 500);
                conn.commit();
            } catch (Exception e) {
                anyFailure.set(true);
            } finally {
                if (conn != null) {
                    try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignored) {}
                    JdbcUtil.close(conn);
                }
            }
        });

        // 事务 B：A → B（同样按字典序，不会死锁）
        Thread threadB = new Thread(() -> {
            Connection conn = null;
            try {
                conn = demo.getConnection();
                startLatch.await();
                long a = demo.lockAndRead(conn, "A");
                long b = demo.lockAndRead(conn, "B");
                demo.updateBalance(conn, "A", a + 300);
                demo.updateBalance(conn, "B", b - 300);
                conn.commit();
            } catch (Exception e) {
                anyFailure.set(true);
            } finally {
                if (conn != null) {
                    try { conn.rollback(); conn.setAutoCommit(true); } catch (Exception ignored) {}
                    JdbcUtil.close(conn);
                }
            }
        });

        threadA.start();
        threadB.start();
        startLatch.countDown();
        threadA.join(8000);
        threadB.join(8000);

        assertFalse("固定加锁顺序不应产生死锁", anyFailure.get());

        // 验证总金额守恒
        Connection conn = demo.getConnection();
        try {
            long totalA = demo.readBalance(conn, "A");
            long totalB = demo.readBalance(conn, "B");
            assertEquals("总金额应守恒", 20000, totalA + totalB);
        } finally {
            JdbcUtil.close(conn);
        }
    }
}
