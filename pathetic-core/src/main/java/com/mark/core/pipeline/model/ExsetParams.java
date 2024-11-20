package com.mark.core.pipeline.model;

import lombok.Data;

/**
 * <p>描述: 幂等参数 </p>
 * <p>创建时间: 2024/8/28 17:02 </p>
 *
 * @author Mark
 */
@Data
public class ExsetParams {

	private Object obj;

	public ExsetParams nx() {
		// 实现 nx 方法逻辑
		return this;
	}

	public ExsetParams ex(Object obj) {
		this.obj = obj;
		return this;
	}

}
