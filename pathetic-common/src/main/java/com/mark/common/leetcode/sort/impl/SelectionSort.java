package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * @ClassName: SelectionSort
 * @Description: 选择排序
 * @Author: Mark
 * @Date: 2024-11-20
 * @Version: 1.0
 **/
public class SelectionSort extends AbstractSort implements Sort {

    /**
     * 首先在未排序序列中找到最小（或最大）元素，存放到排序序列的起始位置。
     * 再从剩余未排序元素中继续寻找最小（或最大）元素，然后放到已排序序列的末尾。
     * 重复第二步，直到所有元素均排序完毕
     *
     * @param nums 待排序数据
     */
    @Override
    public void sort(int[] nums) {

    }

    public static void main(String[] args) {
        SelectionSort sort = new SelectionSort();
        int[] array = {64, 25, 12, 22, 11};
        sort.sort(array);
        System.out.println("Sorted array:");
        for (int value : array) {
            System.out.print(value + " ");
        }
    }
}
