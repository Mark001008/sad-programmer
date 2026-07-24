package com.sad.programmer.redis.common;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Redis 连接工具类。
 *
 * <p>从 classpath:redis.properties 读取配置，维护一个 JedisPool 连接池。</p>
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>redis.host — 主机地址</li>
 *   <li>redis.port — 端口号</li>
 *   <li>redis.password — 密码</li>
 *   <li>redis.database — 数据库索引</li>
 *   <li>redis.timeout — 连接超时（毫秒）</li>
 * </ul>
 *
 * @author sad-programmer
 */
public final class RedisUtil {

    /** Jedis 连接池单例实例，使用 volatile 保证可见性 */
    private static volatile JedisPool jedisPool;

    /**
     * 私有构造方法，防止工具类被实例化。
     *
     * @throws UnsupportedOperationException 始终抛出
     */
    private RedisUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 获取 JedisPool 单例（双重检查锁）。
     *
     * @return JedisPool 实例
     */
    public static JedisPool getPool() {
        if (jedisPool == null) {
            synchronized (RedisUtil.class) {
                if (jedisPool == null) {
                    jedisPool = createPool();
                }
            }
        }
        return jedisPool;
    }

    /**
     * 从连接池获取一个 Jedis 连接。
     *
     * @return Jedis 实例（需在 finally 中调用 {@link #returnResource(Jedis)} 归还）
     */
    public static Jedis getResource() {
        return getPool().getResource();
    }

    /**
     * 归还连接到连接池。
     *
     * @param jedis 要归还的 Jedis 实例，允许 null
     */
    public static void returnResource(Jedis jedis) {
        if (jedis != null) {
            jedis.close();
        }
    }

    /**
     * 关闭连接池，释放所有连接资源。
     */
    public static void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            jedisPool = null;
        }
    }

    /**
     * 重置连接池（测试用），关闭当前连接池并清空单例引用。
     */
    public static void reset() {
        close();
    }

    /**
     * 创建 JedisPool 实例。
     *
     * <p>从 redis.properties 加载配置，设置连接池参数。</p>
     *
     * @return 新创建的 JedisPool 实例
     */
    private static JedisPool createPool() {
        Properties props = loadProperties();
        JedisPoolConfig config = new JedisPoolConfig();
        /** 最大连接数 */
        config.setMaxTotal(20);
        /** 最大空闲连接数 */
        config.setMaxIdle(10);
        /** 最小空闲连接数 */
        config.setMinIdle(2);
        /** 借出时检测连接有效性 */
        config.setTestOnBorrow(true);
        /** 归还时检测连接有效性 */
        config.setTestOnReturn(true);

        String host = props.getProperty("redis.host", "127.0.0.1");
        int port = Integer.parseInt(props.getProperty("redis.port", "6379"));
        String password = props.getProperty("redis.password", "");
        int database = Integer.parseInt(props.getProperty("redis.database", "0"));
        int timeout = Integer.parseInt(props.getProperty("redis.timeout", "3000"));

        if (password == null || password.isEmpty()) {
            return new JedisPool(config, host, port, timeout, null, database);
        }
        return new JedisPool(config, host, port, timeout, password, database);
    }

    /**
     * 从 classpath 加载 redis.properties 配置文件。
     *
     * @return Properties 对象
     * @throws IllegalStateException 当配置文件读取失败时
     */
    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream is = RedisUtil.class.getClassLoader()
                .getResourceAsStream("redis.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 redis.properties", e);
        }
        return props;
    }
}
