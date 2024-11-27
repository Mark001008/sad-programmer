package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * <p>描述: 冒泡排序 </p>
 * <p>创建时间: 2024/11/18 11:02 </p>
 *
 * @author Mark
 */
public class BubbleSort extends AbstractSort implements Sort {

    /**
     * 通过重复地遍历要排序的列表，比较相邻的元素并根据需要交换它们的位置来排序。 这个过程会使得每次遍历后最大的元素“冒泡”到列表的末尾。
     *
     * <p>
     * 从头到尾遍历列表，比较每对相邻的元素。 如果前一个元素大于后一个元素，则交换它们的位置。 重复上述过程，直到没有更多的交换发生，此时列表已排序。
     * <p>
     *
     * @param nums 待排序数据
     */
    @Override
    public void sort(int[] nums) {
        for (int i = 0; i < nums.length; i++) {
            for (int j = i + 1; j < nums.length - 1; j++) {
                if (nums[i] > nums[j]) {
                    swap(nums, i, j);
                }
            }
        }
    }

    public static void main(String[] args) {
        BubbleSort bubbleSort = new BubbleSort();
        int[] arr = {64, 34, 25, 12, 22, 11, 90};
        bubbleSort.sort(arr);
        System.out.println("Sorted array:");
        for (int num : arr) {
            System.out.print(num + " ");
        }
    }
}
