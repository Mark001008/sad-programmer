package com.sad.programmer.framework.spring;

import com.sad.programmer.framework.spring.annotation.MiniAfter;
import com.sad.programmer.framework.spring.annotation.MiniAround;
import com.sad.programmer.framework.spring.annotation.MiniAspect;
import com.sad.programmer.framework.spring.annotation.MiniAutowired;
import com.sad.programmer.framework.spring.annotation.MiniBefore;
import com.sad.programmer.framework.spring.annotation.MiniComponent;
import com.sad.programmer.framework.spring.annotation.MiniPostConstruct;
import com.sad.programmer.framework.spring.annotation.MiniPreDestroy;
import com.sad.programmer.framework.spring.annotation.MiniScope;
import com.sad.programmer.framework.spring.annotation.MiniValue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IoC 容器的核心实现，负责 Bean 的完整生命周期管理。
 *
 * <p>实现的功能：
 * <ul>
 *   <li>包扫描：自动扫描 @MiniComponent 标记的类</li>
 *   <li>依赖注入：自动注入 @MiniAutowired 标记的字段</li>
 *   <li>配置注入：从 Properties 文件注入 @MiniValue 标记的字段</li>
 *   <li>生命周期：调用 @PostConstruct 和 @PreDestroy 方法</li>
 *   <li>AOP：通过 JDK 动态代理实现 @MiniAspect 切面</li>
 *   <li>作用域：支持 singleton 和 prototype</li>
 * </ul></p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class DefaultBeanFactory implements BeanFactory {

    /** Bean 定义表：beanName → BeanDefinition。 */
    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>();

    /** 单例缓存池：beanName → Bean 实例。 */
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();

    /** 正在创建中的 Bean 名称集合（用于检测循环依赖）。 */
    private final Set<String> creatingBeans = new HashSet<>();

    /** 早期暴露的 Bean（三级缓存，用于解决循环依赖）。 */
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();

    /** Bean 的后处理器列表。 */
    private final List<BeanPostProcessor> postProcessors = new ArrayList<>();

    /** 配置属性（从 properties 文件加载）。 */
    private final Properties configProperties = new Properties();

    /** 切面类集合（用于延迟创建切面实例）。 */
    private final Set<Class<?>> aspectClasses = new HashSet<>();

    /** 事件监听器注册表：事件类型 → 监听器列表。 */
    private final Map<Class<? extends ApplicationEvent>, List<ApplicationListener<?>>> eventListeners
            = new ConcurrentHashMap<>();

    /**
     * 扫描指定包路径下所有 @MiniComponent 标记的类，完成 Bean 注册和初始化。
     *
     * @param basePackage 扫描的根包路径
     */
    public void scan(String basePackage) {
        // 1. 加载配置文件
        loadProperties();

        // 2. 扫描并注册 BeanDefinition
        Set<Class<?>> componentClasses = scanComponentClasses(basePackage);
        for (Class<?> clazz : componentClasses) {
            registerBeanDefinition(clazz);
        }

        // 3. 记录切面类（不在这里创建实例，延迟到 AOP 代理时从容器获取）
        for (Class<?> clazz : componentClasses) {
            if (clazz.isAnnotationPresent(MiniAspect.class)) {
                aspectClasses.add(clazz);
            }
        }

        // 4. 添加 AOP 后处理器
        postProcessors.add(new AopBeanPostProcessor(this));

        // 5. 创建所有单例 Bean（触发依赖注入和生命周期回调）
        for (BeanDefinition bd : beanDefinitionMap.values()) {
            if (bd.isSingleton()) {
                getBean(bd.getBeanName());
            }
        }

        // 6. 自动注册事件监听器
        registerEventListeners();
    }

    /**
     * 加载 classpath 下的 application.properties 配置文件。
     */
    public void loadProperties() {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("application.properties");
        if (is != null) {
            try {
                configProperties.load(is);
            } catch (IOException e) {
                throw new RuntimeException("加载配置文件失败", e);
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                    // 关闭流异常忽略
                }
            }
        }
    }

    /**
     * 递归扫描指定包下所有包含 @MiniComponent 注解的类。
     *
     * @param basePackage 根包路径
     * @return 扫描到的组件类集合
     */
    private Set<Class<?>> scanComponentClasses(String basePackage) {
        Set<Class<?>> classes = new HashSet<>();
        String path = basePackage.replace('.', '/');
        try {
            java.net.URL url = Thread.currentThread().getContextClassLoader().getResource(path);
            if (url == null) {
                return classes;
            }
            java.io.File dir = new java.io.File(url.toURI());
            scanDirectory(dir, basePackage, classes);
        } catch (Exception e) {
            throw new RuntimeException("扫描包路径失败: " + basePackage, e);
        }
        return classes;
    }

    /**
     * 递归扫描目录下的 .class 文件。
     *
     * @param dir         目录
     * @param packageName 当前包名
     * @param classes     结果集合
     */
    private void scanDirectory(java.io.File dir, String packageName, Set<Class<?>> classes) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (java.io.File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                try {
                    Class<?> clazz = Class.forName(className);
                    if (clazz.isAnnotationPresent(MiniComponent.class)) {
                        classes.add(clazz);
                    }
                } catch (ClassNotFoundException ignored) {
                    // 无法加载的类跳过
                }
            }
        }
    }

    /**
     * 将一个组件类注册为 BeanDefinition。
     *
     * @param clazz 组件类
     */
    public void registerBeanDefinition(Class<?> clazz) {
        MiniComponent component = clazz.getAnnotation(MiniComponent.class);
        String beanName = component.value().isEmpty()
                ? decapitalize(clazz.getSimpleName())
                : component.value();

        BeanDefinition bd = new BeanDefinition(beanName, clazz.getName(), clazz);

        // 处理 @MiniScope
        MiniScope scope = clazz.getAnnotation(MiniScope.class);
        if (scope != null) {
            bd.setScope(scope.value());
        }

        // 处理 @PostConstruct 和 @PreDestroy
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(MiniPostConstruct.class)) {
                bd.setInitMethodName(method.getName());
            }
            if (method.isAnnotationPresent(MiniPreDestroy.class)) {
                bd.setDestroyMethodName(method.getName());
            }
        }

        beanDefinitionMap.put(beanName, bd);
    }

    /**
     * 注册切面类（延迟创建实例）。
     *
     * @param aspectClass 切面类
     */
    public void registerAspectClass(Class<?> aspectClass) {
        aspectClasses.add(aspectClass);
    }

    /**
     * 获取所有切面类。
     *
     * @return 切面类集合
     */
    public Set<Class<?>> getAspectClasses() {
        return aspectClasses;
    }

    /**
     * 根据切面类获取切面实例（从容器中获取单例）。
     *
     * @param aspectClass 切面类
     * @return 切面实例
     */
    public Object getAspectInstance(Class<?> aspectClass) {
        String beanName = decapitalize(aspectClass.getSimpleName());
        return getBean(beanName);
    }

    /**
     * 注册一个事件监听器。
     *
     * @param eventType  监听的事件类型
     * @param listener   监听器实例
     * @param <E>        事件泛型
     */
    @SuppressWarnings("unchecked")
    public <E extends ApplicationEvent> void addEventListener(
            Class<E> eventType, ApplicationListener<E> listener) {
        eventListeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(listener);
    }

    /**
     * 发布应用事件，通知所有匹配的监听器。
     *
     * <p>遍历事件类型的继承链，找到所有监听该事件类型及其父类的监听器，
     * 依次调用 onApplicationEvent 方法。</p>
     *
     * @param event 应用事件
     */
    @Override
    @SuppressWarnings("unchecked")
    public void publishEvent(ApplicationEvent event) {
        Class<?> eventType = event.getClass();
        // 沿继承链向上查找所有匹配的监听器
        while (eventType != null && ApplicationEvent.class.isAssignableFrom(eventType)) {
            List<ApplicationListener<?>> listeners = eventListeners.get(eventType);
            if (listeners != null) {
                for (ApplicationListener listener : listeners) {
                    listener.onApplicationEvent(event);
                }
            }
            eventType = eventType.getSuperclass();
        }
    }

    /**
     * 自动注册容器中所有 ApplicationListener 实现。
     *
     * <p>在所有单例 Bean 创建完成后调用，解析每个 ApplicationListener 的泛型参数
     * 以确定其监听的事件类型。</p>
     */
    @SuppressWarnings("unchecked")
    private void registerEventListeners() {
        for (Object bean : singletonObjects.values()) {
            if (bean instanceof ApplicationListener) {
                ApplicationListener<?> listener = (ApplicationListener<?>) bean;
                Class<?> eventType = resolveEventType(listener);
                if (eventType != null) {
                    addEventListener((Class<ApplicationEvent>) eventType,
                            (ApplicationListener) listener);
                }
            }
        }
    }

    /**
     * 解析 ApplicationListener 实现类的泛型参数，确定监听的事件类型。
     *
     * @param listener 监听器实例
     * @return 监听的事件类型，无法解析时返回 ApplicationEvent.class
     */
    private Class<?> resolveEventType(ApplicationListener<?> listener) {
        for (java.lang.reflect.Type type : listener.getClass().getGenericInterfaces()) {
            if (type instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) type;
                if (pt.getRawType() == ApplicationListener.class) {
                    java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                    if (arg instanceof Class) {
                        return (Class<?>) arg;
                    }
                }
            }
        }
        return ApplicationEvent.class;
    }

    @Override
    public Object getBean(String beanName) {
        BeanDefinition bd = beanDefinitionMap.get(beanName);
        if (bd == null) {
            throw new RuntimeException("不存在名为 [" + beanName + "] 的 Bean");
        }
        if (bd.isSingleton()) {
            Object instance = singletonObjects.get(beanName);
            if (instance == null) {
                instance = createBean(bd);
                singletonObjects.put(beanName, instance);
            }
            return instance;
        }
        // prototype 每次创建新实例
        return createBean(bd);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> requiredType) {
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitionMap.entrySet()) {
            if (requiredType.isAssignableFrom(entry.getValue().getBeanClass())) {
                return (T) getBean(entry.getKey());
            }
        }
        throw new RuntimeException("不存在类型为 [" + requiredType.getName() + "] 的 Bean");
    }

    @Override
    public boolean containsBean(String beanName) {
        return beanDefinitionMap.containsKey(beanName);
    }

    @Override
    public boolean isSingleton(String beanName) {
        BeanDefinition bd = beanDefinitionMap.get(beanName);
        if (bd == null) {
            throw new RuntimeException("不存在名为 [" + beanName + "] 的 Bean");
        }
        return bd.isSingleton();
    }

    /**
     * 创建 Bean 实例，完成完整的生命周期。
     *
     * <p>生命周期流程：
     * 1. 实例化（通过反射调用无参构造函数）
     * 2. 暴露早期引用（放入三级缓存，解决循环依赖）
     * 3. 属性注入（@MiniAutowired、@MiniValue）
     * 4. BeanPostProcessor#postProcessBeforeInitialization
     * 5. 初始化（@PostConstruct）
     * 6. BeanPostProcessor#postProcessAfterInitialization（AOP 代理在此生成）
     * 7. 放入单例池</p>
     *
     * @param bd Bean 定义
     * @return Bean 实例
     */
    private Object createBean(BeanDefinition bd) {
        String beanName = bd.getBeanName();

        // 循环依赖检测
        if (creatingBeans.contains(beanName)) {
            Object early = earlySingletonObjects.get(beanName);
            if (early != null) {
                return early;
            }
            throw new RuntimeException("检测到循环依赖: " + beanName);
        }
        creatingBeans.add(beanName);

        try {
            // 1. 实例化
            Object instance = bd.getBeanClass().newInstance();

            // 2. 暴露早期引用（用于解决循环依赖）
            if (bd.isSingleton()) {
                earlySingletonObjects.put(beanName, instance);
            }

            // 3. 属性注入
            injectProperties(instance, bd);

            // 4. BeanPostProcessor - 初始化前
            for (BeanPostProcessor bpp : postProcessors) {
                instance = bpp.postProcessBeforeInitialization(instance, beanName);
            }

            // 5. 初始化（@PostConstruct）
            invokeInitMethod(instance, bd);

            // 6. BeanPostProcessor - 初始化后（AOP 代理在这一步生成）
            for (BeanPostProcessor bpp : postProcessors) {
                instance = bpp.postProcessAfterInitialization(instance, beanName);
            }

            // 7. 自动注册事件监听器
            if (instance instanceof ApplicationListener) {
                ApplicationListener<?> listener = (ApplicationListener<?>) instance;
                Class<?> eventType = resolveEventType(listener);
                if (eventType != null) {
                    addEventListener((Class<ApplicationEvent>) eventType,
                            (ApplicationListener) listener);
                }
            }

            // 8. 清理早期引用
            earlySingletonObjects.remove(beanName);

            return instance;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("创建 Bean 失败: " + beanName, e);
        } finally {
            creatingBeans.remove(beanName);
        }
    }

    /**
     * 注入 Bean 的属性，处理 @MiniAutowired 和 @MiniValue 注解。
     *
     * @param instance Bean 实例
     * @param bd       Bean 定义
     * @throws IllegalAccessException 字段访问异常
     */
    private void injectProperties(Object instance, BeanDefinition bd) throws IllegalAccessException {
        Class<?> clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                // 处理 @MiniAutowired
                if (field.isAnnotationPresent(MiniAutowired.class)) {
                    field.setAccessible(true);
                    Object dependency = resolveDependency(field);
                    field.set(instance, dependency);
                }
                // 处理 @MiniValue
                if (field.isAnnotationPresent(MiniValue.class)) {
                    field.setAccessible(true);
                    String key = field.getAnnotation(MiniValue.class).value();
                    String value = configProperties.getProperty(key);
                    if (value == null) {
                        throw new RuntimeException("配置项 [" + key + "] 不存在");
                    }
                    field.set(instance, convertValue(value, field.getType()));
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * 解析依赖，从容器中查找匹配的 Bean。
     *
     * @param field 需要注入的字段
     * @return 依赖的 Bean 实例
     */
    private Object resolveDependency(Field field) {
        Class<?> fieldType = field.getType();

        // 先按类型查找
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitionMap.entrySet()) {
            if (fieldType.isAssignableFrom(entry.getValue().getBeanClass())) {
                candidates.add(entry.getKey());
            }
        }

        if (candidates.isEmpty()) {
            MiniAutowired autowired = field.getAnnotation(MiniAutowired.class);
            if (!autowired.required()) {
                return null;
            }
            throw new RuntimeException("找不到类型为 [" + fieldType.getName() + "] 的 Bean");
        }

        if (candidates.size() == 1) {
            return getBean(candidates.get(0));
        }

        // 多个候选，按字段名匹配
        String fieldName = field.getName();
        if (beanDefinitionMap.containsKey(fieldName)) {
            return getBean(fieldName);
        }

        throw new RuntimeException("找到多个类型为 [" + fieldType.getName()
                + "] 的 Bean: " + candidates + "，请用字段名区分");
    }

    /**
     * 调用初始化方法（@PostConstruct）。
     *
     * @param instance Bean 实例
     * @param bd       Bean 定义
     */
    private void invokeInitMethod(Object instance, BeanDefinition bd) {
        if (bd.getInitMethodName() != null) {
            try {
                Method method = instance.getClass().getDeclaredMethod(bd.getInitMethodName());
                method.setAccessible(true);
                method.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("调用初始化方法失败: " + bd.getInitMethodName(), e);
            }
        }
    }

    /**
     * 关闭容器，销毁所有单例 Bean。
     *
     * <p>遍历所有单例 Bean，调用 @PreDestroy 标记的方法。</p>
     */
    public void close() {
        for (Map.Entry<String, Object> entry : singletonObjects.entrySet()) {
            BeanDefinition bd = beanDefinitionMap.get(entry.getKey());
            if (bd != null && bd.getDestroyMethodName() != null) {
                try {
                    Method method = entry.getValue().getClass()
                            .getDeclaredMethod(bd.getDestroyMethodName());
                    method.setAccessible(true);
                    method.invoke(entry.getValue());
                } catch (Exception e) {
                    throw new RuntimeException("销毁 Bean 失败: " + entry.getKey(), e);
                }
            }
        }
        singletonObjects.clear();
        beanDefinitionMap.clear();
    }

    /**
     * 注册一个 BeanPostProcessor。
     *
     * @param processor 后处理器
     */
    public void addBeanPostProcessor(BeanPostProcessor processor) {
        postProcessors.add(processor);
    }

    /**
     * 将字符串值转换为指定类型。
     *
     * @param value      字符串值
     * @param targetType 目标类型
     * @return 转换后的值
     */
    private Object convertValue(String value, Class<?> targetType) {
        if (targetType == String.class) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value);
        }
        throw new RuntimeException("不支持的类型转换: " + targetType.getName());
    }

    /**
     * 将类名首字母小写，作为默认 Bean 名称。
     *
     * @param className 类名
     * @return 首字母小写的类名
     */
    public String decapitalize(String className) {
        if (className == null || className.isEmpty()) {
            return className;
        }
        char[] chars = className.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * 切面信息，包含通知类型、方法和切面类。
     */
    public static class AspectInfo {

        /** 通知类型：before、after、around。 */
        private final String adviceType;

        /** 通知方法。 */
        private final Method adviceMethod;

        /** 切面类（延迟获取实例）。 */
        private final Class<?> aspectClass;

        /**
         * 构造切面信息。
         *
         * @param adviceType   通知类型
         * @param adviceMethod 通知方法
         * @param aspectClass  切面类
         */
        public AspectInfo(String adviceType, Method adviceMethod, Class<?> aspectClass) {
            this.adviceType = adviceType;
            this.adviceMethod = adviceMethod;
            this.aspectClass = aspectClass;
        }

        /**
         * 获取通知类型。
         *
         * @return 通知类型
         */
        public String getAdviceType() {
            return adviceType;
        }

        /**
         * 获取通知方法。
         *
         * @return 通知方法
         */
        public Method getAdviceMethod() {
            return adviceMethod;
        }

        /**
         * 获取切面类。
         *
         * @return 切面类
         */
        public Class<?> getAspectClass() {
            return aspectClass;
        }
    }
}
