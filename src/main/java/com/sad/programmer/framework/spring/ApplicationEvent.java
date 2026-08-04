package com.sad.programmer.framework.spring;

import java.util.EventObject;

/**
 * 应用事件基类，所有自定义事件应继承此类。
 *
 * <p>基于 JDK 的 EventObject，携带事件源对象和时间戳。
 * 事件发布后由 {@link ApplicationListener} 异步或同步消费。</p>
 *
 * @author sad-programmer
 * @since 1.0.0
 */
public abstract class ApplicationEvent extends EventObject {

    /** 事件创建时间戳（毫秒）。 */
    private final long timestamp;

    /**
     * 构造应用事件。
     *
     * @param source 事件源对象（发布事件的对象）
     */
    public ApplicationEvent(Object source) {
        super(source);
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 获取事件创建时间戳。
     *
     * @return 时间戳（毫秒）
     */
    public long getTimestamp() {
        return timestamp;
    }
}
