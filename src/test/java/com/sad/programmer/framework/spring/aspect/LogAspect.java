package com.sad.programmer.framework.spring.aspect;

import com.sad.programmer.framework.spring.ProceedingJoinPoint;
import com.sad.programmer.framework.spring.annotation.MiniAfter;
import com.sad.programmer.framework.spring.annotation.MiniAround;
import com.sad.programmer.framework.spring.annotation.MiniAspect;
import com.sad.programmer.framework.spring.annotation.MiniBefore;
import com.sad.programmer.framework.spring.annotation.MiniComponent;

/**
 * 日志切面，演示 AOP 的前置、后置、环绕通知。
 *
 * @author sad-programmer
 * @since 1.0.0
 */
@MiniComponent
@MiniAspect
public class LogAspect {

    /** 前置通知执行计数。 */
    private int beforeCount = 0;

    /** 后置通知执行计数。 */
    private int afterCount = 0;

    /** 环绕通知执行计数。 */
    private int aroundCount = 0;

    /**
     * 前置通知：在 OrderService 所有方法执行前记录日志。
     */
    @MiniBefore("OrderService.*")
    public void logBefore() {
        beforeCount++;
    }

    /**
     * 后置通知：在 OrderService 所有方法执行后记录日志。
     */
    @MiniAfter("OrderService.*")
    public void logAfter() {
        afterCount++;
    }

    /**
     * 环绕通知：统计 OrderService.createOrder 方法的执行时间。
     *
     * @param joinPoint 连接点
     * @return 目标方法返回值
     * @throws Throwable 目标方法异常
     */
    @MiniAround("OrderService.createOrder")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        aroundCount++;
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            // 实际项目中这里会记录日志
        }
    }

    /**
     * 获取前置通知执行次数。
     *
     * @return 执行次数
     */
    public int getBeforeCount() {
        return beforeCount;
    }

    /**
     * 获取后置通知执行次数。
     *
     * @return 执行次数
     */
    public int getAfterCount() {
        return afterCount;
    }

    /**
     * 获取环绕通知执行次数。
     *
     * @return 执行次数
     */
    public int getAroundCount() {
        return aroundCount;
    }
}
