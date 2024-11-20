package com.mark.core.pipeline.model;

import com.mark.core.pipeline.enums.StageProcessedResultEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CreateOrderContext extends BaseContext {

	/**
	 * 订单信息
	 */
	private OrderInfoBO orderInfo;

	/**
	 * 构建返回结果
	 */
	public CreateOrderContext ofResult(StageProcessedResultEnum result) {
		super.setResult(result);
		return this;
	}
}