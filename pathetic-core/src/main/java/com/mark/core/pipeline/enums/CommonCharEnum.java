package com.mark.core.pipeline.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>描述:  </p>
 * <p>创建时间: 2024/8/28 16:53 </p>
 *
 * @author Mark
 */
@Getter
@AllArgsConstructor
public enum CommonCharEnum {
	/**
	 * 减号
	 */
	MINUS("MINUS", 1);

	private final String value;

	private final Integer code;

}
