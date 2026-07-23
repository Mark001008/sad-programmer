package com.sad.programmer.database.transaction;

import com.sad.programmer.database.common.JdbcUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * 事务隔离级别测试。
 *
 * <p>通过两个并发连接演示四种隔离级别下的数据异常现象。
 * 每个测试用例模拟真实的并发场景，手动控制 commit 时机。</p>
 */
public class TransactionIsolationDemoTest {

    private TransactionIsolationDemo demo;

    @Before
    public void setUp() throws SQLException {
        demo = new TransactionIsolationDemo();
        demo.initTable();
    }

    @After
    public void tearDown() throws SQLException {
        demo.dropTable();
    }

    // ======================== READ UNCOMMITTED：脏读 ========================

    /**
     * 验证 READ UNCOMMITTED 隔离级别下可以读到未提交的脏数据。
     *
     * <p>场景：事务 A 修改余额但未提交，事务 B 可以读到修改后的值。
     * 如果事务 A 回滚，事务 B 读到的就是"脏数据"。</p>
     */
    @Test
    public void shouldDirtyReadUnderReadUncommitted() throws Exception {
        Connection connA = demo.getConnection(Connection.TRANSACTION_READ_UNCOMMITTED);
        Connection connB = demo.getConnection(Connection.TRANSACTION_READ_UNCOMMITTED);
        try {
            // 事务 A：修改余额但不提交
            connA.setAutoCommit(false);
            demo.updateBalance(connA, "A", 20000);

            // 事务 B：在同一隔离级别下读取，应该能看到未提交的 20000
            connB.setAutoCommit(false);
            long balance = demo.readBalance(connB, "A");
            assertEquals("READ UNCOMMITTED 可以读到未提交的脏数据", 20000, balance);

            // 事务 A 回滚，此时事务 B 之前读到的就是脏数据
            connA.rollback();
            connA.setAutoCommit(true);
        } finally {
            JdbcUtil.close(connA, connB);
        }
    }

    // ======================== READ COMMITTED：不可重复读 ========================

    /**
     * 验证 READ COMMITTED 隔离级别下同一事务内两次读取结果不同（不可重复读）。
     *
     * <p>场景：事务 A 读取余额为 10000，事务 B 修改并提交，事务 A 再次读取变为 20000。
     * 同一事务内两次读取结果不一致。</p>
     */
    @Test
    public void shouldNonRepeatableReadUnderReadCommitted() throws Exception {
        Connection connA = demo.getConnection(Connection.TRANSACTION_READ_COMMITTED);
        Connection connB = demo.getConnection(Connection.TRANSACTION_READ_COMMITTED);
        try {
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // 第一次读取：10000
            long first = demo.readBalance(connA, "A");
            assertEquals(10000, first);

            // 事务 B 修改并提交
            demo.updateBalance(connB, "A", 20000);
            connB.commit();

            // 第二次读取：变成 20000（不可重复读）
            long second = demo.readBalance(connA, "A");
            assertEquals("READ COMMITTED 下同一事务两次读取结果不同", 20000, second);

            connA.rollback();
            connA.setAutoCommit(true);
            connB.setAutoCommit(true);
        } finally {
            JdbcUtil.close(connA, connB);
        }
    }

    // ======================== REPEATABLE READ：可重复读 ========================

    /**
     * 验证 REPEATABLE READ 隔离级别下同一事务内两次读取结果一致。
     *
     * <p>MySQL 默认隔离级别。事务 A 开始后读到快照数据，即使事务 B 修改并提交，
     * 事务 A 再次读取仍然看到快照数据（MVCC 保证）。</p>
     */
    @Test
    public void shouldRepeatableReadUnderRepeatableRead() throws Exception {
        Connection connA = demo.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
        Connection connB = demo.getConnection(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // 第一次读取：10000
            long first = demo.readBalance(connA, "A");
            assertEquals(10000, first);

            // 事务 B 修改并提交
            demo.updateBalance(connB, "A", 20000);
            connB.commit();

            // 第二次读取：仍然 10000（MVCC 快照读）
            long second = demo.readBalance(connA, "A");
            assertEquals("REPEATABLE READ 下同一事务两次读取结果一致", 10000, second);

            connA.rollback();
            connA.setAutoCommit(true);
            connB.setAutoCommit(true);
        } finally {
            JdbcUtil.close(connA, connB);
        }
    }

    // ======================== SERIALIZABLE：完全串行 ========================

    /**
     * 验证 SERIALIZABLE 隔离级别下读操作会加锁。
     *
     * <p>SERIALIZABLE 下所有 SELECT 都隐式加 LOCK IN SHARE MODE。
     * 事务 A 读取后，事务 B 的写操作会等待事务 A 提交后才能执行。</p>
     *
     * <p>本测试验证：事务 A 读取后，事务 B 的 update 会阻塞，
     * 事务 A 提交后事务 B 才能继续。</p>
     */
    @Test(timeout = 10000)
    public void shouldSerializeReadAndWrite() throws Exception {
        Connection connA = demo.getConnection(Connection.TRANSACTION_SERIALIZABLE);
        Connection connB = demo.getConnection(Connection.TRANSACTION_SERIALIZABLE);
        try {
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // 事务 A 读取（加共享锁）
            long balance = demo.readBalance(connA, "A");
            assertEquals(10000, balance);

            // 事务 B 尝试修改：在 SERIALIZABLE 下会被阻塞
            // 用子线程执行 update，主线程等待一段时间后提交事务 A
            final boolean[] updateDone = {false};
            final boolean[] updateSuccess = {false};
            Thread updater = new Thread(() -> {
                try {
                    demo.updateBalance(connB, "A", 30000);
                    connB.commit();
                    updateSuccess[0] = true;
                } catch (SQLException e) {
                    // 被阻塞后如果主线程超时，会抛异常
                } finally {
                    updateDone[0] = true;
                }
            });
            updater.start();

            // 等待一小段时间，确认 update 被阻塞
            Thread.sleep(500);
            assertFalse("SERIALIZABLE 下 update 应被阻塞", updateDone[0]);

            // 事务 A 提交，释放共享锁
            connA.commit();

            // 等待 update 线程完成
            updater.join(3000);
            assertTrue("事务 A 提交后 update 应完成", updateDone[0]);
            assertTrue("update 应成功", updateSuccess[0]);

            connA.setAutoCommit(true);
            connB.setAutoCommit(true);
        } finally {
            JdbcUtil.close(connA, connB);
        }
    }

    // ======================== 默认隔离级别验证 ========================

    /**
     * 验证 MySQL 默认隔离级别是 REPEATABLE READ。
     */
    @Test
    public void shouldDefaultToRepeatableRead() throws SQLException {
        Connection conn = demo.getConnection();
        try {
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, conn.getTransactionIsolation());
        } finally {
            JdbcUtil.close(conn);
        }
    }
}
