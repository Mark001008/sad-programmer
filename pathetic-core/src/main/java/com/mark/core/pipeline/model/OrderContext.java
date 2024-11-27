package com.mark.core.pipeline.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>描述: 订单上下文 </p>
 * <p>创建时间: 2024/8/28 17:24 </p>
 *
 * @author Mark
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderContext extends BaseContext {

    private String orderId;
}
