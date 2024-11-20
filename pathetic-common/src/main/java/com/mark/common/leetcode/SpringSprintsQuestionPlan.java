package com.mark.common.leetcode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>描述: 2024 春招冲刺百题计划 </p>
 * <p>创建时间: 2024/11/6 13:56 </p>
 *
 * @author Mark
 */
public class SpringSprintsQuestionPlan {

	/**
	 * 59. 螺旋矩阵 II 给你一个 m 行 n 列的矩阵 matrix ，请按照 顺时针螺旋顺序 ，返回矩阵中的所有元素。
	 * <p>
	 *
	 * @solution 模拟
	 * @solution 按层模拟
	 *
	 * <p>
	 */
	public List<Integer> spiralOrder(int[][] matrix) {
		if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
			return Collections.emptyList();
		}

		List<Integer> result = new ArrayList<>();

		int rows = matrix.length;
		int columns = matrix[0].length;

		boolean[][] markMatrix = new boolean[rows][columns];
		int[][] step = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

		int stepIndex = 0;
		int row = 0;
		int column = 0;
		for (int i = 0; i < rows * columns; i++) {
			result.add(matrix[row][column]);

			markMatrix[row][column] = true;

			int nextRow = row + step[stepIndex][0];
			int nextColumn = column + step[stepIndex][1];

			if (nextRow < 0 || nextRow >= rows || nextColumn < 0 || nextColumn >= columns
					|| markMatrix[nextRow][nextColumn]) {
				stepIndex = (stepIndex + 1) % 4;
			}

			row += step[stepIndex][0];
			column += step[stepIndex][1];
		}

		return result;
	}

	/**
	 * 59. 螺旋矩阵 II
	 */
	public List<Integer> spiralOrder2(int[][] matrix) {
		return Collections.emptyList();
	}

	/**
	 * 289. 生命游戏 给你一个 m x n 的矩阵 board ，表示一个游戏板，其中每个单元格不是 0 就是 1 。 输入：board = [[0,1,0],[0,0,1],[1,1,1],[0,0,0]]
	 * <p>
	 *
	 * @solution 辅助数据 模拟
	 * @solution 额外标识
	 *
	 * <p>
	 */
	public void gameOfLife(int[][] board) {
		int[] neighbors = {0, 1, -1};

		int rows = board.length;
		int columns = board[0].length;

		// 创建复制数组 copyBoard
		int[][] copyBoard = new int[rows][columns];

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < columns; col++) {
				copyBoard[row][col] = board[row][col];
			}
		}

		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				int liveNeighbors = 0;

				// 遍历当前单元格的所有相邻单元格：(i,j) -> (-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 0), (0, 1), (1, -1), (1, 0), (1, 1)
				for (int i = 0; i < 3; i++) {
					for (int j = 0; j < 3; j++) {
						if (!(neighbors[i] == 0 && neighbors[j] == 0)) {
							int r = row + neighbors[i];
							int c = column + neighbors[j];
							// 相邻的坐标是否越界
							if (r >= 0 && r < rows && c >= 0 && c < columns && copyBoard[r][c] == 1) {
								liveNeighbors++;
							}
						}
					}
				}
				//活细胞  周围八个位置的活细胞数少于两个，则该位置活细胞死亡;周围八个位置有超过三个活细胞，则该位置活细胞死亡;周围八个位置有超过三个活细胞，则该位置活细胞死亡
				if (copyBoard[row][column] == 1 && (liveNeighbors < 2 || liveNeighbors > 3)) {
					board[row][column] = 0;
				}
				//死细胞  周围正好有三个活细胞，则该位置死细胞复活
				if (copyBoard[row][column] == 0 && liveNeighbors == 3) {
					board[row][column] = 1;
				}
			}
		}
	}

	/**
	 * 48. 旋转图像 给你一幅由 N × N 矩阵表示的图像，其中每个像素的大小为 4 字节。请你设计一种算法，将图像旋转 90 度。
	 * <p>
	 *
	 * @solution 辅助数据
	 * @solution 原地旋转
	 * @solution 水平反装 + 对称反转
	 *
	 * <p>
	 */
	public void rotate(int[][] matrix) {

	}


	public static void main(String[] args) {

		SpringSprintsQuestionPlan questionPlan = new SpringSprintsQuestionPlan();

		List<Integer> integers = questionPlan.spiralOrder(new int[][] {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}});
		for (Integer integer : integers) {
			System.out.print(integer + " ");
		}
	}

}
