package com.mark.core.flow;

/**
 * <p>描述: 执行其接口 </p>
 * <p>创建时间: 2024/11/14 9:42 </p>
 *
 * @author Mark
 */
public interface Executable {

	/**
	 * 执行
	 *
	 * @param slotIndex 插槽索引
	 *
	 * @throws Exception 执行异常
	 */
	void execute(Integer slotIndex) throws Exception;

}
