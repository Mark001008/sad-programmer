package com.mark.core.pipeline.model;

import com.mark.core.pipeline.enums.StageProcessedResultEnum;
import lombok.Data;

import java.util.Map;

/**
 * <p>描述: 基础上下文,用于传递参数 </p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 *
 * @author Mark
 */
@Data
public class BaseContext {

	/**
	 * 扩展信息
	 */
	private Map<String, String> extInfo;

	/**
	 * 处理结果
	 */
	private StageProcessedResultEnum result;

}
