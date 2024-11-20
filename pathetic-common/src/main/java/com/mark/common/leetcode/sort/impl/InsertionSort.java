package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * <p>描述: 插入排序 </p>
 * <p>创建时间: 2024/11/20 </p>
 *
 * @author Mark
 */
public class InsertionSort extends AbstractSort implements Sort {

	/**
	 * 通过构建有序序列，对于未排序数据，在已排序序列中从后向前扫描，找到相应位置并插入。
	 *
	 * <p>
	 * 从第一个元素开始，认为第一个元素已经排序。 取出下一个元素，在已排序的元素序列中从后向前扫描。 如果该元素（已排序）大于新元素，将该元素移到下一个位置。 重复步骤3，直到找到已排序的元素小于或等于新元素的位置。
	 * 将新元素插入到该位置后。 重复步骤2-5，直到所有元素都排序完成。
	 * <p>
	 *
	 * @param nums 待排序数据
	 */
	@Override
	public void sort(int[] nums) {
		for (int i = 1; i < nums.length; i++) {
			int temp = nums[i];
			int j = i - 1;
			while (j >= 0 && nums[j] > temp) {
				nums[j + 1] = nums[j];
				j--;
			}
			nums[j + 1] = temp;
		}
	}

	public static void main(String[] args) {
		InsertionSort sort = new InsertionSort();
		int[] arr = {64, 34, 25, 12, 22, 11, 90};
		sort.sort(arr);
		System.out.println("Sorted array:");
		for (int num : arr) {
			System.out.print(num + " ");
		}
	}
}
