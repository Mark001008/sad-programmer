package com.mark.core.pipeline;

import com.mark.core.pipeline.config.StageConfig;
import com.mark.core.pipeline.enums.CommonCharEnum;
import com.mark.core.pipeline.enums.StageProcessedResultEnum;
import com.mark.core.pipeline.model.CreateOrderContext;
import com.mark.core.pipeline.model.CreateOrderHistoryBO;
import com.mark.core.pipeline.model.OrderInfoBO;
import lombok.RequiredArgsConstructor;

/**
 * <p>描述: 创建订单记录</p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 *
 * @author Mark
 */
@StageConfig(name = "CreateOrderHistory")
@RequiredArgsConstructor
public class CreateOrderHistory extends AbstractStageIdempotent<CreateOrderContext> {

    private final OrderCore createHistory;

    /**
     * 提供一个用于子类处理业务逻辑的入口
     *
     * @param context 上下文
     * @return 执行结果
     */
    @Override
    protected CreateOrderContext executeBusinessLogic(CreateOrderContext context) {
        OrderInfoBO orderInfo = context.getOrderInfo();
        CreateOrderHistoryBO historyDTO = createHistory.buildHistory(orderInfo);
        historyDTO.setPaidCntLeft(historyDTO.getCount());
        Long historyId = createHistory.createHistory(historyDTO);
        return context.ofResult(historyId == null ? StageProcessedResultEnum.WRITE_USER_RIGHTS_FLOW_DB_FAIL
                : StageProcessedResultEnum.SUCCESS);
    }

    /**
     * 获取幂等key，返回null代表不需要幂等
     *
     * @param context 上下文
     * @return 幂等key
     */
    @Override
    public String getIdempotentKey(CreateOrderContext context) {
        OrderInfoBO orderInfo = context.getOrderInfo();
        // 幂等key：idle-local-CreateOrderHistory-{bizCode}-{userId}-{cardId}
        return orderInfo.getIdempotentKey() != null ? orderInfo.getIdempotentKey()
                : TairKeysConstantsTools.generateKey(CommonCharEnum.MINUS.getValue(),
                String.valueOf(orderInfo.getUserId()), String.valueOf(orderInfo.getId()));
    }
}