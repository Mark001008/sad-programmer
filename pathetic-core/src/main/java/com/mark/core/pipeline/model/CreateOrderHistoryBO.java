package com.mark.core.pipeline.model;

import lombok.Data;

/**
 * <p>描述: 订单留痕 </p>
 * <p>创建时间: 2024/8/28 17:14 </p>
 *
 * @author Mark
 */
@Data
public class CreateOrderHistoryBO {

    private Integer paidCntLeft;

    private Integer count;

}
