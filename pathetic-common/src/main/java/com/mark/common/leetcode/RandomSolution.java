package com.mark.common.leetcode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * <p>描述: 每日随机 </p>
 * <p>创建时间: 2024/11/19 18:55 </p>
 *
 * @author Mark
 */
public class RandomSolution {

    private RandomSolution randomSolution;

    @BeforeEach
    public void setUp() {
        randomSolution = new RandomSolution();
    }

    int n;

    List<Integer>[] g;

    /**
     * <p>描述: 新增道路查询后的最短距离 I </p>
     * <p>创建时间: 2024/11/19 18:56 </p>
     *
     * @param n       整数
     * @param queries 维整数数组
     * @return int[] 最短路径的长度
     */
    public int[] shortestDistanceAfterQueries(int n, int[][] queries) {

        this.n = n;
        this.g = new ArrayList[n];

        for (int i = 0; i < n; i++) {
            g[i] = new ArrayList<>();
        }

        for (int i = 0; i < n - 1; i++) {
            g[i].add(i + 1);
        }

        int[] ans = new int[queries.length];
        for (int i = 0; i < queries.length; i++) {
            int x = queries[i][0];
            int y = queries[i][1];
            g[x].add(y);
            ans[i] = bfs();
        }

        return ans;
    }

    private int bfs() {
        boolean[] visited = new boolean[n];

        ArrayDeque<Integer> que = new ArrayDeque<>();
        que.offer(0);

        visited[0] = true;
        int step = 1;
        while (!que.isEmpty()) {
            int size = que.size();
            for (int i = 0; i < size; i++) {
                Integer x = que.poll();
                for (Integer y : g[x]) {
                    if (y == n - 1) {
                        return step;
                    }
                    if (!visited[y]) {
                        que.offer(y);
                        visited[y] = true;
                    }
                }
            }
            step++;
        }
        return -1;
    }

    @Test
    public void shortestDistanceAfterQueries_test() {
        int n = 4;
        int[][] queries = {{0, 1}, {1, 2}, {2, 3}};
        int[] expected = {3, 2, 1};
        int[] result = randomSolution.shortestDistanceAfterQueries(n, queries);
        assertArrayEquals(expected, result);
    }

    public int[] shortestDistanceAfterQueriesII(int n, int[][] queries) {
        return null;
    }


}
