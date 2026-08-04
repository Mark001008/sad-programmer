package com.sad.programmer.framework.mybatis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Mapper 代理处理器，通过 JDK 动态代理将接口方法调用转换为 SQL 执行。
 *
 * <p>这是 MyBatis 最核心的设计，等价于 MyBatis 的 MapperProxy。
 * 原理与手写 Spring 的 AOP 代理完全一致——都是 JDK 动态代理。</p>
 *
 * <p>调用流程：
 * <pre>
 * UserMapper mapper = sqlSession.getMapper(UserMapper.class);
 * User user = mapper.selectById(1L);
 *
 * 实际执行的是：
 * MapperProxy.invoke(proxy, selectById, [1L])
 *   → 解析 statementId: "com.xxx.UserMapper.selectById"
 *   → 获取 MappedStatement
 *   → executor.query(ms, {id: 1})
 *   → JDBC PreparedStatement 执行 SQL
 *   → ResultSet 映射为 User 对象
 * </pre></p>
 *
 * @param <T> Mapper 接口类型
 * @author sad-programmer
 * @since 1.0.0
 */
public class MiniMapperProxy<T> implements InvocationHandler {

    /** 配置中心。 */
    private final MiniConfiguration configuration;

    /** Mapper 接口类。 */
    private final Class<T> mapperInterface;

    /** SQL 执行器。 */
    private final MiniExecutor executor;

    /**
     * 构造 Mapper 代理。
     *
     * @param configuration  配置中心
     * @paramMapperInterface Mapper 接口类
     * @param executor       SQL 执行器
     */
    public MiniMapperProxy(MiniConfiguration configuration, Class<T> mapperInterface,
                           MiniExecutor executor) {
        this.configuration = configuration;
        this.mapperInterface = mapperInterface;
        this.executor = executor;
    }

    /**
     * 代理方法调用拦截。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>Object 方法（toString/hashCode/equals）直接调用</li>
     *   <li>根据接口全限定名 + 方法名拼接 statementId</li>
     *   <li>从 Configuration 获取 MappedStatement</li>
     *   <li>根据 SQL 类型调用 executor.query 或 executor.update</li>
     * </ol></p>
     *
     * @param proxy  代理对象
     * @param method 被调用的方法
     * @param args   方法参数
     * @return SQL 执行结果
     * @throws Throwable 执行异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法直接调用
        if (Object.class.equals(method.getDeclaringClass())) {
            return method.invoke(this, args);
        }

        // 拼接 statementId: com.xxx.UserMapper.selectById
        String statementId = mapperInterface.getName() + "." + method.getName();

        // 获取映射语句
        MiniMappedStatement ms = configuration.getMappedStatement(statementId);
        if (ms == null) {
            throw new RuntimeException("未找到映射语句: " + statementId);
        }

        // 构造参数对象
        Object params = buildParams(method, args);

        // 根据 SQL 类型分发执行
        switch (ms.getSqlType()) {
            case SELECT:
                // 查询：根据返回类型决定返回单个对象还是列表
                if (java.util.List.class.isAssignableFrom(method.getReturnType())) {
                    return executor.query(ms, params);
                } else {
                    java.util.List<?> list = executor.query(ms, params);
                    return list.isEmpty() ? null : list.get(0);
                }
            case INSERT:
            case UPDATE:
            case DELETE:
                return executor.update(ms, params);
            default:
                throw new RuntimeException("不支持的 SQL 类型: " + ms.getSqlType());
        }
    }

    /**
     * 构造方法参数对象。
     *
     * <p>如果只有一个参数，直接返回；如果有多个参数，包装为 Map。</p>
     *
     * @param method 方法
     * @param args   参数数组
     * @return 参数对象
     */
    private Object buildParams(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        if (args.length == 1) {
            return args[0];
        }
        // 多参数：使用参数名（arg0, arg1, ...）或 Map
        // 简化处理：使用 Map
        java.util.Map<String, Object> paramMap = new java.util.HashMap<String, Object>();
        String[] paramNames = java.lang.reflect.Proxy.isProxyClass(args.getClass())
                ? new String[0]
                : getParamNames(method);
        for (int i = 0; i < args.length; i++) {
            String key = (i < paramNames.length) ? paramNames[i] : "arg" + i;
            paramMap.put(key, args[i]);
        }
        return paramMap;
    }

    /**
     * 获取方法参数名称（JDK 1.8 需要 -parameters 编译选项）。
     *
     * <p>简化实现：直接使用 arg0, arg1, ... 作为参数名。</p>
     *
     * @param method 方法
     * @return 参数名称数组
     */
    private String[] getParamNames(Method method) {
        // JDK 1.8 默认不保留参数名，使用 arg0, arg1, ...
        String[] names = new String[method.getParameterCount()];
        for (int i = 0; i < names.length; i++) {
            names[i] = "arg" + i;
        }
        return names;
    }
}
