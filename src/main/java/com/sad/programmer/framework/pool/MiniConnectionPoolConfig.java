package com.sad.programmer.framework.pool;

/**
 * 连接池配置，控制池的行为参数。
 *
 * <p>等价于 HikariConfig / DruidDataSource 的配置项。
 * 所有参数均有合理默认值，可按需调整。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniConnectionPoolConfig {

    /** 最小空闲连接数：池启动时创建的连接数，空闲时也保持这个数量。 */
    private int minIdle = 5;

    /** 最大活跃连接数：池中允许的最大连接数（含借出的）。 */
    private int maxActive = 20;

    /** 最大等待毫秒数：池耗尽时等待获取连接的最大时间，超时抛异常。 */
    private long maxWaitMillis = 3000;

    /** 连接验证 SQL：借出前执行此 SQL 验证连接是否有效。 */
    private String validationQuery = "SELECT 1";

    /** 连接最大存活时间（毫秒）：超过此时间的连接会被回收。 */
    private long maxLifetimeMillis = 30 * 60 * 1000;

    /** 泄漏检测阈值（毫秒）：连接借出超过此时间未归还，打印警告。 */
    private long leakDetectionThreshold = 60 * 1000;

    /**
     * 获取最小空闲连接数。
     *
     * @return 最小空闲连接数
     */
    public int getMinIdle() {
        return minIdle;
    }

    /**
     * 设置最小空闲连接数。
     *
     * @param minIdle 最小空闲连接数
     */
    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    /**
     * 获取最大活跃连接数。
     *
     * @return 最大活跃连接数
     */
    public int getMaxActive() {
        return maxActive;
    }

    /**
     * 设置最大活跃连接数。
     *
     * @param maxActive 最大活跃连接数
     */
    public void setMaxActive(int maxActive) {
        this.maxActive = maxActive;
    }

    /**
     * 获取最大等待毫秒数。
     *
     * @return 最大等待毫秒数
     */
    public long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    /**
     * 设置最大等待毫秒数。
     *
     * @param maxWaitMillis 最大等待毫秒数
     */
    public void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * 获取连接验证 SQL。
     *
     * @return 验证 SQL
     */
    public String getValidationQuery() {
        return validationQuery;
    }

    /**
     * 设置连接验证 SQL。
     *
     * @param validationQuery 验证 SQL
     */
    public void setValidationQuery(String validationQuery) {
        this.validationQuery = validationQuery;
    }

    /**
     * 获取连接最大存活时间。
     *
     * @return 最大存活时间（毫秒）
     */
    public long getMaxLifetimeMillis() {
        return maxLifetimeMillis;
    }

    /**
     * 设置连接最大存活时间。
     *
     * @param maxLifetimeMillis 最大存活时间（毫秒）
     */
    public void setMaxLifetimeMillis(long maxLifetimeMillis) {
        this.maxLifetimeMillis = maxLifetimeMillis;
    }

    /**
     * 获取泄漏检测阈值。
     *
     * @return 泄漏检测阈值（毫秒）
     */
    public long getLeakDetectionThreshold() {
        return leakDetectionThreshold;
    }

    /**
     * 设置泄漏检测阈值。
     *
     * @param leakDetectionThreshold 泄漏检测阈值（毫秒）
     */
    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold;
    }
}
