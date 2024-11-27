package com.mark.core.pipeline;

import com.mark.core.pipeline.config.Idempotent;
import com.mark.core.pipeline.config.Stage;
import com.mark.core.pipeline.enums.CommonCharEnum;
import com.mark.core.pipeline.enums.StageProcessedResultEnum;
import com.mark.core.pipeline.model.BaseContext;
import com.mark.core.pipeline.model.ExgetResult;
import com.mark.core.pipeline.model.ExsetParams;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ObjectUtils;

/**
 * <p>描述: 幂等模版类 </p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 * 实现Stage、Idempotent接口 在Stage方法执行的前后加上幂等的校验，其中幂等的实现依赖于TairStringInterface接口
 *
 * @author Mark
 */
public abstract class AbstractStageIdempotent<T extends BaseContext> implements Stage<T>, Idempotent<T> {

    public static final String APP_NAME = "***";

    /**
     * 【RDB】用于幂等缓存的实现
     */
    @Autowired
    private TairStringInterface tairStringInterface;

    /**
     * 提供一个用于子类处理业务逻辑的入口
     *
     * @param context 上下文
     * @return 执行结果
     */
    protected abstract T executeBusinessLogic(T context);

    @Override
    public T execute(T context) {
        // 拿到当前执行的Stage名称
        String simpleName = this.getClass().getSimpleName();
        String idempotentKey = getIdempotentKey(context);
        String key = TairKeysConstantsTools.generateKey(CommonCharEnum.MINUS.getValue(), APP_NAME, simpleName,
                idempotentKey);
        try {
            // 如果已经处理过，则无需执行业务逻辑，直接跳过当前流程
            if (idempotentKey != null && getMark(key, context)) {
                return context;
            }
            // 执行业务逻辑
            context = executeBusinessLogic(context);
            // 标记为处理过（仅当业务执行成功时）
            if (idempotentKey != null && context.getResult() != null && context.getResult().isSuccess() && (!marked(key,
                    context))) {
                // 执行失败，则抛出异常
                context.setResult(StageProcessedResultEnum.IDEMPOTENT_FAIL);
            }
        } catch (Exception e) {
            context.setResult(StageProcessedResultEnum.IDEMPOTENT_FAIL);
        }
        return context;
    }

    /**
     * 检查是否存在标记值
     *
     * @param key 幂等key
     * @return 是否存在
     */
    private boolean getMark(String key, T context) {
        ExgetResult<String> result = tairStringInterface.exget(key);
        if (result != null && !ObjectUtils.isEmpty(result.getValue())) {
            return "1".equals(result.getValue());
        }
        return false;
    }

    /**
     * 标记
     *
     * @param key 幂等key
     * @return 标记结果
     */
    private boolean marked(String key, T context) {
        // 带上nx、ex参数
        ExsetParams params = new ExsetParams().nx().ex(SwitchConfigBiz.PIPE_LINE_IDEMPOTENT_EXPIRE_TIME);
        String reply = tairStringInterface.exset(key, "1", params);
        return "OK".equals(reply);
    }
}