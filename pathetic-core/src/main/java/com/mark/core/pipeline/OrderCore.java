package com.mark.core.pipeline;

import com.mark.core.pipeline.model.CreateOrderHistoryBO;
import com.mark.core.pipeline.model.OrderInfoBO;

import org.springframework.stereotype.Component;

/**
 * <p>描述: 订单核心逻辑 </p>
 * <p>创建时间: 2024/8/28 17:10 </p>
 *
 * @author Mark
 */
@Component
public class OrderCore {

    public Long createOrder(OrderInfoBO createOrder) {
        return null;
    }

    public CreateOrderHistoryBO buildHistory(OrderInfoBO orderInfoBO) {
        return null;
    }

    public Long createHistory(CreateOrderHistoryBO historyDTO) {
        return null;
    }
}
