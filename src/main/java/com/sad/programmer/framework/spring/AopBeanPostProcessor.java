package com.sad.programmer.framework.spring;

import com.sad.programmer.framework.spring.DefaultBeanFactory.AspectInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AOP 后处理器，负责为匹配切入点的 Bean 创建 JDK 动态代理。
 *
 * <p>在 Bean 初始化之后，检查当前 Bean 的方法是否匹配任何切入点表达式。
 * 如果匹配，则为该 Bean 创建 JDK 动态代理，在方法执行前后织入通知。</p>
 *
 * <p>核心流程：
 * <ol>
 *   <li>遍历所有切入点表达式，检查是否匹配当前 Bean</li>
 *   <li>如果匹配，收集该 Bean 所有方法对应的通知</li>
 *   <li>创建 JDK 动态代理，在 InvocationHandler 中执行通知链</li>
 * </ol></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class AopBeanPostProcessor implements BeanPostProcessor {

    /** IoC 容器（用于获取切面实例）。 */
    private final DefaultBeanFactory beanFactory;

    /**
     * 构造 AOP 后处理器。
     *
     * @param beanFactory IoC 容器
     */
    public AopBeanPostProcessor(DefaultBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // AOP 不在初始化前处理
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 查找匹配当前 Bean 的所有通知
        List<AspectInfo> matchedAdvices = findMatchedAdvices(bean);
        if (matchedAdvices.isEmpty()) {
            return bean;
        }

        // 创建 JDK 动态代理
        return Proxy.newProxyInstance(
                bean.getClass().getClassLoader(),
                bean.getClass().getInterfaces(),
                new AopInvocationHandler(bean, matchedAdvices, beanFactory)
        );
    }

    /**
     * 查找匹配指定 Bean 的所有切面通知。
     *
     * @param bean Bean 实例
     * @return 匹配的通知列表
     */
    private List<AspectInfo> findMatchedAdvices(Object bean) {
        List<AspectInfo> matched = new ArrayList<>();
        String beanClassName = bean.getClass().getSimpleName();

        for (Class<?> aspectClass : beanFactory.getAspectClasses()) {
            for (Method method : aspectClass.getDeclaredMethods()) {
                String pointcut = null;
                String adviceType = null;
                if (method.isAnnotationPresent(com.sad.programmer.framework.spring.annotation.MiniBefore.class)) {
                    pointcut = method.getAnnotation(com.sad.programmer.framework.spring.annotation.MiniBefore.class).value();
                    adviceType = "before";
                } else if (method.isAnnotationPresent(com.sad.programmer.framework.spring.annotation.MiniAfter.class)) {
                    pointcut = method.getAnnotation(com.sad.programmer.framework.spring.annotation.MiniAfter.class).value();
                    adviceType = "after";
                } else if (method.isAnnotationPresent(com.sad.programmer.framework.spring.annotation.MiniAround.class)) {
                    pointcut = method.getAnnotation(com.sad.programmer.framework.spring.annotation.MiniAround.class).value();
                    adviceType = "around";
                }
                if (pointcut != null && isMatch(pointcut, beanClassName)) {
                    matched.add(new AspectInfo(adviceType, method, aspectClass));
                }
            }
        }
        return matched;
    }

    /**
     * 判断切入点表达式是否匹配目标类。
     *
     * @param pointcut   切入点表达式（如 "OrderService.*"）
     * @param simpleName 目标类简单名
     * @return true 表示匹配
     */
    private boolean isMatch(String pointcut, String simpleName) {
        String className = pointcut.contains(".")
                ? pointcut.substring(0, pointcut.lastIndexOf('.'))
                : pointcut;
        if ("*".equals(className)) {
            return true;
        }
        return className.equals(simpleName);
    }

    /**
     * AOP 调用处理器，在方法执行前后织入切面通知。
     */
    private static class AopInvocationHandler implements InvocationHandler {

        /** 目标对象。 */
        private final Object target;

        /** 匹配的通知列表。 */
        private final List<AspectInfo> advices;

        /** IoC 容器（用于获取切面实例）。 */
        private final DefaultBeanFactory beanFactory;

        /**
         * 构造调用处理器。
         *
         * @param target      目标对象
         * @param advices     通知列表
         * @param beanFactory IoC 容器
         */
        AopInvocationHandler(Object target, List<AspectInfo> advices, DefaultBeanFactory beanFactory) {
            this.target = target;
            this.advices = advices;
            this.beanFactory = beanFactory;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 执行前置通知
            for (AspectInfo advice : advices) {
                if ("before".equals(advice.getAdviceType())) {
                    Object aspectInstance = beanFactory.getAspectInstance(advice.getAspectClass());
                    advice.getAdviceMethod().invoke(aspectInstance);
                }
            }

            Object result = null;
            Throwable throwable = null;

            // 2. 检查是否有环绕通知
            AspectInfo aroundAdvice = null;
            for (AspectInfo advice : advices) {
                if ("around".equals(advice.getAdviceType())) {
                    aroundAdvice = advice;
                    break;
                }
            }

            try {
                if (aroundAdvice != null) {
                    // 环绕通知：传入 ProceedingJoinPoint，由切面决定是否执行目标方法
                    ProceedingJoinPoint joinPoint = new ProceedingJoinPoint(target, method, args);
                    Object aspectInstance = beanFactory.getAspectInstance(aroundAdvice.getAspectClass());
                    result = aroundAdvice.getAdviceMethod().invoke(aspectInstance, joinPoint);
                } else {
                    // 直接执行目标方法
                    result = method.invoke(target, args);
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                throwable = e.getTargetException();
            } catch (Exception e) {
                throwable = e;
            }

            // 3. 执行后置通知
            for (AspectInfo advice : advices) {
                if ("after".equals(advice.getAdviceType())) {
                    Object aspectInstance = beanFactory.getAspectInstance(advice.getAspectClass());
                    advice.getAdviceMethod().invoke(aspectInstance);
                }
            }

            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }
}
