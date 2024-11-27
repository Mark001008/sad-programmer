package com.mark.common.leetcode.sort.impl;

import com.mark.common.leetcode.sort.AbstractSort;
import com.mark.common.leetcode.sort.Sort;

/**
 * @ClassName: MergeSort
 * @Description: 归并排序
 * @Author: Mark
 * @Date: 2024-11-20
 * @Version: 1.0
 **/
public class MergeSort extends AbstractSort implements Sort {

    /**
     * 分解： 如果数组的长度大于1，则计算中间索引 mid，将数组分成两个子数组。 递归地对左半部分和右半部分进行排序。
     * 递归排序： 对左半部分数组 [left, mid] 进行归并排序。 对右半部分数组 [mid + 1,right] 进行归并排序。
     * 合并： 使用两个指针分别指向左半部分和右半部分的起始位置。 比较两个指针所指的元素，将较小的元素放入临时数组中，并移动相应的指针。
     * 如果某个子数组已经处理完，将另一个子数组的剩余部分直接复制到临时数组中。 最后将临时数组中的元素复制回原数组。
     *
     * @param nums 待排序数据
     */
    @Override
    public void sort(int[] nums) {

    }


    public static void main(String[] args) {
        MergeSort sort = new MergeSort();
        int[] array = {64, 25, 12, 22, 11};
        sort.sort(array);
        System.out.println("Sorted array:");
        for (int value : array) {
            System.out.print(value + " ");
        }
    }
}
