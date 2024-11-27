package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * @ClassName: QuickSort
 * @Description: 快速排序
 * @Author: Mark
 * @Date: 2024-11-20
 * @Version: 1.0
 **/
public class QuickSort extends AbstractSort implements Sort {

    /**
     * 选择基准值：从数列中挑出一个元素，称为“基准”（pivot）。
     * 分区操作：重新排列数列，所有比基准值小的元素摆放在基准前面，所有比基准值大的元素摆在基准后面（相同的数可以到任一边）。在这个分割结束之后，该基准就处于数列的中间位置。这个称为分割（partition）操作。
     * 递归排序：递归地将小于基准值的子数列和大于基准值的子数列进行快速排序。
     *
     * @param nums 待排序数据
     */
    @Override
    public void sort(int[] nums) {
        quickSort(nums, 0, nums.length - 1);
    }

    private void quickSort(int[] nums, int low, int high) {
        if (low < high) {
            int pivotIndex = partition(nums, low, high);
            // 递归排序左子数组
            quickSort(nums, low, pivotIndex - 1);
            // 递归排序右子数组
            quickSort(nums, pivotIndex + 1, high);
        }
    }

    private int partition(int[] nums, int low, int high) {
        // 选择最后一个元素作为基准
        int pivot = nums[high];
        // 指向小于基准值的最后一个元素
        int i = low - 1;

        for (int j = low; j < high; j++) {
            if (nums[j] <= pivot) {
                i++;
                // 交换元素
                swap(nums, i, j);
            }
        }
        // 将基准值放到中间位置
        swap(nums, i + 1, high);
        return i + 1;
    }

    public static void main(String[] args) {
        QuickSort sort = new QuickSort();
        int[] array = {64, 25, 12, 22, 11};
        sort.sort(array);
        System.out.println("Sorted array:");
        for (int value : array) {
            System.out.print(value + " ");
        }
    }
}
