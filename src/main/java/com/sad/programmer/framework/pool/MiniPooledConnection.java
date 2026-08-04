package com.sad.programmer.framework.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * 池化连接包装器，通过 JDK 动态代理拦截 close() 调用。
 *
 * <p>核心设计（连接池的灵魂）：
 * <pre>
 * 应用层调用 connection.close()
 *   → 代理拦截
 *   → 不是真正关闭连接
 *   → 而是将连接归还到连接池
 * </pre></p>
 *
 * <p>这个设计保证了：
 * <ul>
 *   <li>应用代码可以正常使用 try-with-resources 关闭连接</li>
 *   <li>连接不会被真正关闭，而是被复用</li>
 *   <li>归还时重置连接状态（autoCommit、readOnly 等）</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniPooledConnection {

    /**
     * 创建池化连接的代理实例。
     *
     * <p>通过 JDK 动态代理包装真实 Connection，拦截 close() 方法实现归还逻辑。</p>
     *
     * @param realConnection 真实数据库连接
     * @param pool           所属连接池
     * @return 池化连接代理
     */
    public static Connection wrap(final Connection realConnection,
                                  final MiniConnectionPool pool) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                new PooledConnectionHandler(realConnection, pool)
        );
    }

    /**
     * 池化连接的调用处理器。
     *
     * <p>核心逻辑：
     * <ul>
     *   <li>close()：归还连接到池，不真正关闭</li>
     *   <li>isClosed()：检查连接是否已归还</li>
     *   <li>其他方法：委托给真实连接</li>
     * </ul></p>
     */
    private static class PooledConnectionHandler implements InvocationHandler {

        /** 真实数据库连接。 */
        private final Connection realConnection;

        /** 所属连接池。 */
        private final MiniConnectionPool pool;

        /** 借出时间戳（用于泄漏检测）。 */
        private final long borrowTime;

        /** 是否已归还。 */
        private volatile boolean returned;

        /**
         * 构造调用处理器。
         *
         * @param realConnection 真实连接
         * @param pool           所属连接池
         */
        PooledConnectionHandler(Connection realConnection, MiniConnectionPool pool) {
            this.realConnection = realConnection;
            this.pool = pool;
            this.borrowTime = System.currentTimeMillis();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            // close()：归还连接到池
            if ("close".equals(methodName)) {
                if (!returned) {
                    returned = true;
                    pool.returnConnection(realConnection);
                }
                return null;
            }

            // isClosed()：检查是否已归还
            if ("isClosed".equals(methodName)) {
                return returned;
            }

            // unwrap()：返回真实连接
            if ("unwrap".equals(methodName) && args.length == 1) {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(realConnection)) {
                    return realConnection;
                }
            }

            // 其他方法：委托给真实连接
            if (returned) {
                throw new RuntimeException("连接已归还，无法调用方法: " + methodName);
            }
            return method.invoke(realConnection, args);
        }
    }
}
