package com.mark.service;

import com.mark.core.pipeline.model.Result;

/**
 * <p>描述: 订单能力 </p>
 * <p>创建时间: 2024/8/28 17:26 </p>
 *
 * @author Mark
 */
public interface OrderService extends BasicService {

	/**
	 * 创建订单
	 *
	 * @param bizCode 业务线
	 *
	 * @return 消费额度是否成功
	 */
	Result<Boolean> createOrder(String bizCode);
}
