package com.mark.core.pipeline.model;

import lombok.Data;

/**
 * <p>描述: 订单信息 </p>
 * <p>创建时间: 2024/8/28 16:51 </p>
 *
 * @author Mark
 */
@Data
public class OrderInfoBO {

	private char[] userId;

	private Long id;

	private String idempotentKey;

	private String bizCode;

	private String templateCode;

}
