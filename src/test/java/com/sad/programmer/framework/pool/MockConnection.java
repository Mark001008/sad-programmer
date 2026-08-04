package com.sad.programmer.framework.pool;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 数据库连接，用于测试连接池。
 *
 * <p>不连接真实数据库，只记录状态变化，验证连接池的行为。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MockConnection implements Connection {

    /** 全局连接 ID 生成器。 */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);

    /** 连接 ID。 */
    private final int id = ID_GENERATOR.incrementAndGet();

    /** 是否已关闭。 */
    private volatile boolean closed;

    /** 自动提交状态。 */
    private boolean autoCommit = true;

    /** 创建时间戳。 */
    private final long createTime = System.currentTimeMillis();

    /**
     * 获取连接 ID。
     *
     * @return 连接 ID
     */
    public int getId() {
        return id;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public boolean isValid(int timeout) {
        return !closed;
    }

    @Override
    public void commit() {}

    @Override
    public void rollback() {}

    // ========== Connection 接口空实现 ==========

    @Override public Statement createStatement() { return null; }
    @Override public PreparedStatement prepareStatement(String sql) { return null; }
    @Override public CallableStatement prepareCall(String sql) { return null; }
    @Override public String nativeSQL(String sql) { return sql; }
    @Override public void setReadOnly(boolean readOnly) {}
    @Override public boolean isReadOnly() { return false; }
    @Override public void setCatalog(String catalog) {}
    @Override public String getCatalog() { return null; }
    @Override public void setTransactionIsolation(int level) {}
    @Override public int getTransactionIsolation() { return 0; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public Statement createStatement(int a, int b) { return null; }
    @Override public PreparedStatement prepareStatement(String s, int a, int b) { return null; }
    @Override public CallableStatement prepareCall(String s, int a, int b) { return null; }
    @Override public Map<String, Class<?>> getTypeMap() { return null; }
    @Override public void setTypeMap(Map<String, Class<?>> m) {}
    @Override public void setHoldability(int h) {}
    @Override public int getHoldability() { return 0; }
    @Override public Savepoint setSavepoint() { return null; }
    @Override public Savepoint setSavepoint(String n) { return null; }
    @Override public void rollback(Savepoint s) {}
    @Override public void releaseSavepoint(Savepoint s) {}
    @Override public Statement createStatement(int a, int b, int c) { return null; }
    @Override public PreparedStatement prepareStatement(String s, int a, int b, int c) { return null; }
    @Override public CallableStatement prepareCall(String s, int a, int b, int c) { return null; }
    @Override public PreparedStatement prepareStatement(String s, int a) { return null; }
    @Override public PreparedStatement prepareStatement(String s, int[] a) { return null; }
    @Override public PreparedStatement prepareStatement(String s, String[] a) { return null; }
    @Override public Clob createClob() { return null; }
    @Override public Blob createBlob() { return null; }
    @Override public NClob createNClob() { return null; }
    @Override public SQLXML createSQLXML() { return null; }
    @Override public void setClientInfo(String n, String v) {}
    @Override public void setClientInfo(Properties p) {}
    @Override public String getClientInfo(String n) { return null; }
    @Override public Properties getClientInfo() { return null; }
    @Override public Array createArrayOf(String t, Object[] e) { return null; }
    @Override public Struct createStruct(String t, Object[] a) { return null; }
    @Override public void setSchema(String s) {}
    @Override public String getSchema() { return null; }
    @Override public void abort(Executor e) {}
    @Override public void setNetworkTimeout(Executor e, int m) {}
    @Override public int getNetworkTimeout() { return 0; }
    @Override public <T> T unwrap(Class<T> iface) { return null; }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    @Override public DatabaseMetaData getMetaData() { return null; }
}
