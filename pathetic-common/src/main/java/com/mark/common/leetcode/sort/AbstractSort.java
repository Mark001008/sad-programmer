package com.mark.common.leetcode.sort;

/**
 * <p>描述: 抽象排序 </p>
 * <p>创建时间: 2024/11/18 10:58 </p>
 *
 * @author Mark
 */
public abstract class AbstractSort {

	/**
	 * 交换数组中两个元素的位置
	 *
	 * @param nums 数组
	 * @param i    索引1
	 * @param j    索引2
	 */
	protected void swap(int[] nums, int i, int j) {
		int temp = nums[i];
		nums[i] = nums[j];
		nums[j] = temp;
	}

}
