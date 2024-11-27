package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * @ClassName: ShellSort
 * @Description: 希尔排序
 * @Author: Mark
 * @Date: 2024-11-20
 * @Version: 1.0
 **/
public class ShellSort extends AbstractSort implements Sort {

    /**
     * 选择增量序列：选择一个增量序列，常见的增量序列有：1, 3, 5, 7, ... 或者 N/2, N/4, N/8, ...，其中N是数组的长度。
     * 分组：根据选定的增量，将数组分成多个子数组。例如，如果增量为3，那么下标为0, 3, 6, ... 的元素组成一个子数组，下标为1, 4, 7, ... 的元素组成另一个子数组，以此类推。
     * 插入排序：对每个子数组进行插入排序。
     * 减少增量：减少增量值，重复上述分组和插入排序的过程，直到增量为1。
     * 最终排序：当增量为1时，对整个数组进行最后一次插入排序，确保数组完全有序。
     *
     * @param nums 待排序数据
     */
    @Override
    public void sort(int[] nums) {

    }

    public static void main(String[] args) {
        ShellSort sort = new ShellSort();
        int[] array = {64, 25, 12, 22, 11};
        sort.sort(array);
        System.out.println("Sorted array:");
        for (int value : array) {
            System.out.print(value + " ");
        }
    }
}
