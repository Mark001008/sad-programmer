package com.mark.core.pipeline;

import com.mark.core.pipeline.config.AppSwitch;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>描述: 执行链 </p>
 * <p>创建时间: 2024/8/28 17:03 </p>
 *
 * @author Mark
 */
public class SwitchConfigBiz {


	public static final Object PIPE_LINE_IDEMPOTENT_EXPIRE_TIME = null;

	/**
	 * 业务执行链路
	 */
	@AppSwitch(des = "pipeline中不同业务的执行链路配置")
	public static final Map<String, List<String>> PIPE_LINE_BIZ_EXECUTE_CHAIN = new ConcurrentHashMap<>();


	static {
		/**
		 * 创建订单
		 */
		PIPE_LINE_BIZ_EXECUTE_CHAIN.put("createOrder", Arrays.asList(
				// 创建权益卡
				"CreateOrder",
				// 创建流水记录
				"CreateOrderHistory"));
	}


}
