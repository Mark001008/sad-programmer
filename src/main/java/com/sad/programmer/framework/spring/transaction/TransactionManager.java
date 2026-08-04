package com.sad.programmer.framework.spring.transaction;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 事务管理器，通过 ThreadLocal 绑定数据库连接实现事务控制。
 *
 * <p>核心原理（等价于 Spring 的 DataSourceTransactionManager）：
 * <ul>
 *   <li>每个线程通过 ThreadLocal 持有一个独立的 Connection</li>
 *   <li>begin() 获取连接并关闭自动提交</li>
 *   <li>同一线程内的所有数据库操作共享同一个 Connection</li>
 *   <li>commit() 提交事务，rollback() 回滚事务</li>
 *   <li>release() 关闭连接并清除 ThreadLocal，防止内存泄漏</li>
 * </ul></p>
 *
 * <p>设计要点：
 * <pre>
 * Thread-1 ──→ [Connection-1] ←── begin/commit/rollback
 * Thread-2 ──→ [Connection-2] ←── begin/commit/rollback
 * Thread-3 ──→ [Connection-3] ←── begin/commit/rollback
 *
 * 每个线程独立的连接，互不干扰。
 * 同一线程内的多个 DAO 操作共享同一个连接 → 同一个事务。
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class TransactionManager {

    /** ThreadLocal 绑定的连接持有器：每个线程一个连接。 */
    private static final ThreadLocal<Connection> CONNECTION_HOLDER = new ThreadLocal<>();

    /** 连接工厂，用于获取数据库连接。 */
    private final ConnectionFactory connectionFactory;

    /**
     * 构造事务管理器。
     *
     * @param connectionFactory 连接工厂
     */
    public TransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 开启事务：获取连接并关闭自动提交。
     *
     * <p>如果当前线程已有连接（嵌套事务），直接复用。
     * 这对应 Spring 的 REQUIRED 传播行为。</p>
     *
     * @return 当前事务状态
     */
    public TransactionStatus begin() {
        Connection conn = CONNECTION_HOLDER.get();
        if (conn != null) {
            // 已有事务，嵌套调用直接复用（REQUIRED 语义）
            return new TransactionStatus(conn, true);
        }
        try {
            conn = connectionFactory.getConnection();
            conn.setAutoCommit(false);
            CONNECTION_HOLDER.set(conn);
            return new TransactionStatus(conn, false);
        } catch (SQLException e) {
            throw new RuntimeException("开启事务失败", e);
        }
    }

    /**
     * 提交事务。
     *
     * @param status 事务状态
     */
    public void commit(TransactionStatus status) {
        if (status.isNested()) {
            // 嵌套事务不提交，由外层事务统一提交
            return;
        }
        Connection conn = status.getConnection();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.commit();
            }
        } catch (SQLException e) {
            throw new RuntimeException("提交事务失败", e);
        }
    }

    /**
     * 回滚事务。
     *
     * @param status 事务状态
     */
    public void rollback(TransactionStatus status) {
        if (status.isNested()) {
            // 嵌套事务不回滚，由外层事务统一处理
            return;
        }
        Connection conn = status.getConnection();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException("回滚事务失败", e);
        }
    }

    /**
     * 释放连接并清除 ThreadLocal。
     *
     * <p>必须在 finally 块中调用，防止连接泄漏和 ThreadLocal 内存泄漏。</p>
     *
     * @param status 事务状态
     */
    public void release(TransactionStatus status) {
        if (status.isNested()) {
            return;
        }
        Connection conn = status.getConnection();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.setAutoCommit(true);
                conn.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("释放连接失败", e);
        } finally {
            CONNECTION_HOLDER.remove();
        }
    }

    /**
     * 获取当前线程绑定的连接。
     *
     * @return 当前线程的连接，未开启事务时返回 null
     */
    public Connection getCurrentConnection() {
        return CONNECTION_HOLDER.get();
    }

    /**
     * 连接工厂接口，抽象连接获取方式。
     *
     * <p>生产环境传入 DataSource 的实现，测试环境传入 Mock 实现。</p>
     */
    public interface ConnectionFactory {

        /**
         * 获取数据库连接。
         *
         * @return 数据库连接
         * @throws SQLException 连接获取失败
         */
        Connection getConnection() throws SQLException;
    }
}
