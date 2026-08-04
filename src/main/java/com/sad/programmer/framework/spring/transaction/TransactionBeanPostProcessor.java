package com.sad.programmer.framework.spring.transaction;

import com.sad.programmer.framework.spring.BeanPostProcessor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * 事务 Bean 后处理器，为 @MiniTransactional 标记的 Bean 创建 AOP 代理。
 *
 * <p>等价于 Spring 的 InfrastructureAdvisorAutoProxyCreator + TransactionInterceptor。
 * 核心流程：
 * <ol>
 *   <li>检查 Bean 的类或方法是否标注了 @MiniTransactional</li>
 *   <li>如果是，创建 JDK 动态代理</li>
 *   <li>代理在方法执行前开启事务，执行后提交，异常时回滚</li>
 * </ol></p>
 *
 * <p>事务的 AOP 拦截顺序：
 * <pre>
 * 调用者 → 事务代理 → [begin → 前置通知 → 目标方法 → 后置通知 → commit/rollback] → 结果
 * </pre></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class TransactionBeanPostProcessor implements BeanPostProcessor {

    /** 事务管理器。 */
    private final TransactionManager transactionManager;

    /**
     * 构造事务 Bean 后处理器。
     *
     * @param transactionManager 事务管理器
     */
    public TransactionBeanPostProcessor(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // 事务代理不在初始化前处理
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 获取目标类（如果是代理，递归获取原始类）
        Class<?> targetClass = getTargetClass(bean);

        // 检查类级别 @MiniTransactional
        boolean classTransactional = targetClass.isAnnotationPresent(MiniTransactional.class);

        // 检查方法级别 @MiniTransactional
        Map<String, Boolean> methodTransactional = new HashMap<>();
        for (Method method : targetClass.getMethods()) {
            if (method.isAnnotationPresent(MiniTransactional.class)) {
                methodTransactional.put(method.getName(), true);
            }
        }

        // 类和方法都没有 @MiniTransactional，直接返回
        if (!classTransactional && methodTransactional.isEmpty()) {
            return bean;
        }

        // 创建 JDK 动态代理
        final boolean isClassTransactional = classTransactional;
        return Proxy.newProxyInstance(
                targetClass.getClassLoader(),
                bean.getClass().getInterfaces(),
                new TransactionInvocationHandler(bean, transactionManager,
                        isClassTransactional, methodTransactional)
        );
    }

    /**
     * 获取目标对象的真实类。
     *
     * <p>如果 Bean 已经是 JDK 动态代理（被其他 BeanPostProcessor 处理过），
     * 递归获取原始目标类。</p>
     *
     * @param bean Bean 实例
     * @return 真实目标类
     */
    private Class<?> getTargetClass(Object bean) {
        if (Proxy.isProxyClass(bean.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(bean);
            if (handler instanceof TransactionInvocationHandler) {
                return getTargetClass(
                        ((TransactionInvocationHandler) handler).getTarget());
            }
        }
        return bean.getClass();
    }

    /**
     * 事务调用处理器，在方法执行前后织入事务控制。
     *
     * <p>核心逻辑：
     * <pre>
     * begin()
     * try {
     *     result = target.method(args)  // 执行目标方法
     *     commit()                      // 成功则提交
     * } catch (Exception e) {
     *     rollback()                    // 异常则回滚
     *     throw e
     * } finally {
     *     release()                     // 释放连接，清除 ThreadLocal
     * }
     * </pre></p>
     */
    private static class TransactionInvocationHandler implements InvocationHandler {

        /** 目标对象。 */
        private final Object target;

        /** 事务管理器。 */
        private final TransactionManager transactionManager;

        /** 类级别是否标注 @MiniTransactional。 */
        private final boolean classTransactional;

        /** 方法级别的 @MiniTransactional 标注：methodName → true。 */
        private final Map<String, Boolean> methodTransactional;

        /**
         * 构造调用处理器。
         *
         * @param target               目标对象
         * @param transactionManager   事务管理器
         * @param classTransactional   类级别是否有 @MiniTransactional
         * @param methodTransactional  方法级别的 @MiniTransactional 标注
         */
        TransactionInvocationHandler(Object target, TransactionManager transactionManager,
                                     boolean classTransactional,
                                     Map<String, Boolean> methodTransactional) {
            this.target = target;
            this.transactionManager = transactionManager;
            this.classTransactional = classTransactional;
            this.methodTransactional = methodTransactional;
        }

        /**
         * 获取目标对象。
         *
         * @return 目标对象
         */
        public Object getTarget() {
            return target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 判断当前方法是否需要事务
            boolean needTransaction = classTransactional
                    || methodTransactional.containsKey(method.getName());

            if (!needTransaction) {
                // 非事务方法，直接调用
                return method.invoke(target, args);
            }

            // 开启事务
            TransactionStatus status = transactionManager.begin();
            try {
                // 执行目标方法
                Object result = method.invoke(target, args);

                // 检查是否被标记为仅回滚
                if (status.isRollbackOnly()) {
                    transactionManager.rollback(status);
                } else {
                    transactionManager.commit(status);
                }
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) {
                // 解包反射调用异常，获取真正的业务异常
                transactionManager.rollback(status);
                throw e.getTargetException();
            } catch (Exception e) {
                // 其他异常直接回滚
                transactionManager.rollback(status);
                throw e;
            } finally {
                // 释放连接，清除 ThreadLocal
                status.markCompleted();
                transactionManager.release(status);
            }
        }
    }
}
