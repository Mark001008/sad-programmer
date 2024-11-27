package com.mark.service.impl;

import com.mark.core.pipeline.factory.OrderFactory;
import com.mark.core.pipeline.model.OrderContext;
import com.mark.core.pipeline.model.Result;
import com.mark.service.OrderService;
import lombok.RequiredArgsConstructor;

/**
 * <p>描述: 订单实现 </p>
 * <p>创建时间: 2024/8/28 17:26 </p>
 *
 * @author Mark
 */
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderFactory orderFactory;

    @Override
    public Result<Boolean> createOrder(String bizCode) {

        OrderContext context = new OrderContext();
        context.setOrderId(bizCode);

        context = orderFactory.execute(context, "createOrder");

        return Result.success(context.getResult().isSuccess());
    }
}