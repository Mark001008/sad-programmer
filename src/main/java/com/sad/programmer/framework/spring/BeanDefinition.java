package com.sad.programmer.framework.spring;

/**
 * Bean 定义元数据，描述一个 Bean 的全部信息。
 *
 * <p>等价于 Spring 中的 BeanDefinition，是容器管理 Bean 的核心数据结构。
 * 包含 Bean 的类名、作用域、初始化方法、销毁方法等。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public class BeanDefinition {

    /** Bean 的名称（通常为首字母小写的类名）。 */
    private final String beanName;

    /** Bean 的全限定类名。 */
    private final String beanClassName;

    /** Bean 的 Class 对象。 */
    private final Class<?> beanClass;

    /** Bean 的作用域：singleton 或 prototype。 */
    private String scope;

    /** 初始化方法名（@PostConstruct 标记的方法）。 */
    private String initMethodName;

    /** 销毁方法名（@PreDestroy 标记的方法）。 */
    private String destroyMethodName;

    /**
     * 构造 Bean 定义。
     *
     * @param beanName      Bean 名称
     * @param beanClassName 全限定类名
     * @param beanClass     Class 对象
     */
    public BeanDefinition(String beanName, String beanClassName, Class<?> beanClass) {
        this.beanName = beanName;
        this.beanClassName = beanClassName;
        this.beanClass = beanClass;
        this.scope = "singleton";
    }

    /**
     * 获取 Bean 名称。
     *
     * @return Bean 名称
     */
    public String getBeanName() {
        return beanName;
    }

    /**
     * 获取全限定类名。
     *
     * @return 全限定类名
     */
    public String getBeanClassName() {
        return beanClassName;
    }

    /**
     * 获取 Class 对象。
     *
     * @return Class 对象
     */
    public Class<?> getBeanClass() {
        return beanClass;
    }

    /**
     * 获取作用域。
     *
     * @return 作用域字符串
     */
    public String getScope() {
        return scope;
    }

    /**
     * 设置作用域。
     *
     * @param scope 作用域字符串
     */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * 判断是否单例。
     *
     * @return true 表示单例
     */
    public boolean isSingleton() {
        return "singleton".equals(scope);
    }

    /**
     * 判断是否原型（每次获取都创建新实例）。
     *
     * @return true 表示原型
     */
    public boolean isPrototype() {
        return "prototype".equals(scope);
    }

    /**
     * 获取初始化方法名。
     *
     * @return 初始化方法名
     */
    public String getInitMethodName() {
        return initMethodName;
    }

    /**
     * 设置初始化方法名。
     *
     * @param initMethodName 初始化方法名
     */
    public void setInitMethodName(String initMethodName) {
        this.initMethodName = initMethodName;
    }

    /**
     * 获取销毁方法名。
     *
     * @return 销毁方法名
     */
    public String getDestroyMethodName() {
        return destroyMethodName;
    }

    /**
     * 设置销毁方法名。
     *
     * @param destroyMethodName 销毁方法名
     */
    public void setDestroyMethodName(String destroyMethodName) {
        this.destroyMethodName = destroyMethodName;
    }

    @Override
    public String toString() {
        return "BeanDefinition{"
                + "beanName='" + beanName + '\''
                + ", scope='" + scope + '\''
                + ", beanClass=" + beanClass.getName()
                + '}';
    }
}
