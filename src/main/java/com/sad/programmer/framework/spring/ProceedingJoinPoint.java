package com.sad.programmer.framework.spring;

import java.lang.reflect.Method;

/**
 * 环绕通知的连接点对象，用于控制目标方法的执行。
 *
 * <p>等价于 Spring AOP 中的 ProceedingJoinPoint，
 * 提供了获取方法信息和调用目标方法的能力。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class ProceedingJoinPoint {

    /** 目标对象。 */
    private final Object target;

    /** 目标方法。 */
    private final Method method;

    /** 方法参数。 */
    private final Object[] args;

    /**
     * 构造连接点。
     *
     * @param target 目标对象
     * @param method 目标方法
     * @param args   方法参数
     */
    public ProceedingJoinPoint(Object target, Method method, Object[] args) {
        this.target = target;
        this.method = method;
        this.args = args;
    }

    /**
     * 获取目标方法名。
     *
     * @return 方法名
     */
    public String getMethodName() {
        return method.getName();
    }

    /**
     * 获取方法参数。
     *
     * @return 参数数组
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * 调用目标方法。
     *
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的异常
     */
    public Object proceed() throws Throwable {
        return method.invoke(target, args);
    }
}
