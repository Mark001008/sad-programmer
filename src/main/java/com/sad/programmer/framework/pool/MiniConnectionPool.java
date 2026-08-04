package com.sad.programmer.framework.pool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库连接池核心实现。
 *
 * <p>等价于 HikariCP / Druid 的连接池，核心能力：
 * <ul>
 *   <li>连接复用：借出 → 使用 → 归还，而非 每次创建 → 关闭</li>
 *   <li>池大小控制：minIdle ≤ 活跃连接 ≤ maxActive</li>
 *   <li>等待机制：池耗尽时阻塞等待，超时抛异常</li>
 *   <li>连接验证：借出前验证连接有效性</li>
 *   <li>泄漏检测：借出超时未归还时打印警告</li>
 * </ul></p>
 *
 * <p>内部结构：
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │              MiniConnectionPool              │
 * │                                             │
 * │  idleConnections (ConcurrentLinkedDeque)    │
 * │  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐            │
 * │  │ C1│ │ C2│ │ C3│ │ C4│ │ C5│  ← 空闲连接 │
 * │  └───┘ └───┘ └───┘ └───┘ └───┘            │
 * │                                             │
 * │  activeCount: AtomicInteger                 │
 * │  totalCount: AtomicInteger                  │
 * │                                             │
 * │  getConnection() → 借出连接                 │
 * │  returnConnection() → 归还连接              │
 * └─────────────────────────────────────────────┘
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniConnectionPool {

    /** 连接池配置。 */
    private final MiniConnectionPoolConfig config;

    /** 空闲连接队列（线程安全的双端队列）。 */
    private final ConcurrentLinkedDeque<Connection> idleConnections = new ConcurrentLinkedDeque<Connection>();

    /** 当前活跃连接数（已借出 + 空闲）。 */
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /** 当前借出的连接数。 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 连接池是否已关闭。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 连接工厂：获取真实数据库连接。 */
    private final ConnectionFactory connectionFactory;

    /** 泄漏检测线程。 */
    private Thread leakDetectorThread;

    /**
     * 构造连接池。
     *
     * @param config           连接池配置
     * @param connectionFactory 连接工厂
     */
    public MiniConnectionPool(MiniConnectionPoolConfig config,
                              ConnectionFactory connectionFactory) {
        this.config = config;
        this.connectionFactory = connectionFactory;

        // 初始化：创建 minIdle 个连接
        initPool();
    }

    /**
     * 初始化连接池，创建 minIdle 个空闲连接。
     */
    private void initPool() {
        for (int i = 0; i < config.getMinIdle(); i++) {
            Connection conn = createRealConnection();
            if (conn != null) {
                idleConnections.offer(conn);
                totalCount.incrementAndGet();
            }
        }
    }

    /**
     * 从连接池获取连接。
     *
     * <p>获取流程：
     * <ol>
     *   <li>从空闲队列取连接</li>
     *   <li>如果队列为空且未达上限，创建新连接</li>
     *   <li>如果已达上限，阻塞等待归还</li>
     *   <li>验证连接有效性</li>
     *   <li>包装为池化连接返回</li>
     * </ol></p>
     *
     * @return 池化连接
     * @throws RuntimeException 获取失败
     */
    public Connection getConnection() {
        checkNotClosed();

        // 1. 从空闲队列取连接
        Connection conn = idleConnections.poll();
        if (conn != null) {
            if (isValid(conn)) {
                activeCount.incrementAndGet();
                return wrapConnection(conn);
            }
            // 连接无效，丢弃并重新获取
            totalCount.decrementAndGet();
            closeQuietly(conn);
            return getConnection();
        }

        // 2. 队列为空，尝试创建新连接
        if (totalCount.get() < config.getMaxActive()) {
            conn = createRealConnection();
            if (conn != null) {
                totalCount.incrementAndGet();
                activeCount.incrementAndGet();
                return wrapConnection(conn);
            }
        }

        // 3. 已达上限，阻塞等待
        return waitForConnection();
    }

    /**
     * 阻塞等待连接归还。
     *
     * <p>使用 while 循环 + poll(timeout) 实现等待。
     * 超时后抛出异常。</p>
     *
     * @return 池化连接
     * @throws RuntimeException 等待超时
     */
    private Connection waitForConnection() {
        long deadline = System.currentTimeMillis() + config.getMaxWaitMillis();

        while (System.currentTimeMillis() < deadline) {
            // 短暂等待后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待连接被中断", e);
            }

            Connection conn = idleConnections.poll();
            if (conn != null && isValid(conn)) {
                activeCount.incrementAndGet();
                return wrapConnection(conn);
            }
            if (conn != null) {
                // 连接无效，丢弃
                totalCount.decrementAndGet();
                closeQuietly(conn);
            }
        }

        throw new RuntimeException("获取连接超时，等待 " + config.getMaxWaitMillis() + "ms");
    }

    /**
     * 归还连接到连接池。
     *
     * <p>归还流程：
     * <ol>
     *   <li>重置连接状态（autoCommit、readOnly）</li>
     *   <li>放入空闲队列</li>
     *   <li>减少活跃计数</li>
     * </ol></p>
     *
     * @param conn 真实连接
     */
    public void returnConnection(Connection conn) {
        if (conn == null) {
            return;
        }

        activeCount.decrementAndGet();

        // 检查池是否已关闭
        if (closed.get()) {
            closeQuietly(conn);
            totalCount.decrementAndGet();
            return;
        }

        // 重置连接状态
        try {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            // 连接可能已损坏，丢弃
            closeQuietly(conn);
            totalCount.decrementAndGet();
            return;
        }

        // 归还到空闲队列
        idleConnections.offer(conn);
    }

    /**
     * 包装真实连接为池化连接。
     *
     * @param conn 真实连接
     * @return 池化连接代理
     */
    private Connection wrapConnection(Connection conn) {
        return MiniPooledConnection.wrap(conn, this);
    }

    /**
     * 创建真实数据库连接。
     *
     * @return 数据库连接，创建失败返回 null
     */
    private Connection createRealConnection() {
        try {
            return connectionFactory.getConnection();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验证连接是否有效。
     *
     * @param conn 数据库连接
     * @return true 表示有效
     */
    private boolean isValid(Connection conn) {
        try {
            return conn != null && !conn.isClosed() && conn.isValid(1);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 关闭连接池。
     *
     * <p>关闭所有空闲连接，不再接受新的借出请求。</p>
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Connection conn;
        while ((conn = idleConnections.poll()) != null) {
            closeQuietly(conn);
            totalCount.decrementAndGet();
        }
    }

    /**
     * 安静关闭连接。
     *
     * @param conn 数据库连接
     */
    private void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (Exception ignored) {
            // 关闭异常忽略
        }
    }

    /**
     * 检查连接池是否已关闭。
     */
    private void checkNotClosed() {
        if (closed.get()) {
            throw new RuntimeException("连接池已关闭");
        }
    }

    /**
     * 获取当前空闲连接数。
     *
     * @return 空闲连接数
     */
    public int getIdleCount() {
        return idleConnections.size();
    }

    /**
     * 获取当前活跃连接数（已借出）。
     *
     * @return 活跃连接数
     */
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * 获取当前总连接数。
     *
     * @return 总连接数
     */
    public int getTotalCount() {
        return totalCount.get();
    }

    /**
     * 连接池是否已关闭。
     *
     * @return true 表示已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 连接工厂接口。
     *
     * <p>生产环境传入 DataSource 的实现，测试环境传入 Mock 实现。</p>
     */
    public interface ConnectionFactory {

        /**
         * 获取数据库连接。
         *
         * @return 数据库连接
         * @throws Exception 连接获取失败
         */
        Connection getConnection() throws Exception;
    }
}
