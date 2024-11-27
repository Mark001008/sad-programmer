package com.mark.core.pipeline.config;

import com.mark.core.pipeline.model.BaseContext;

/**
 * <p>描述: 定义Pipeline中的基本方法类为Stage接口 </p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 *
 * @author Mark
 */
public interface Stage<T extends BaseContext> {

    /**
     * 执行当前阶段的逻辑
     *
     * @param context 上下文
     * @return 执行结果
     */
    T execute(T context);

}