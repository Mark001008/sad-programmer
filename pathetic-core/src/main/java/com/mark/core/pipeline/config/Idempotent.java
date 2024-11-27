package com.mark.core.pipeline.config;

import com.mark.core.pipeline.model.BaseContext;

/**
 * <p>描述: 实现幂等操作的接口定义 </p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 *
 * @author Mark
 */
public interface Idempotent<T extends BaseContext> {

    /**
     * 获取幂等key，返回null代表不需要幂等
     *
     * @param context 上下文
     * @return 幂等key
     */
    String getIdempotentKey(T context);
}