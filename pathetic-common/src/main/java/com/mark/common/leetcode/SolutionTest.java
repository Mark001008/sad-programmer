package com.mark.common.leetcode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>描述: 解决方案 </p>
 * <p>创建时间: 2024/9/18 9:28 </p>
 *
 * @author Mark
 */
public class SolutionTest {

	private SolutionTest solution;

	@BeforeEach
	public void setUp() {
		solution = new SolutionTest();
	}

	/**
	 * 判断字符串中的所有字符是否都是唯一的
	 *
	 * @param astr 字符串
	 *
	 * @return 是否唯一
	 */
	public boolean isUnique(String astr) {
		int[] arr = new int[26];
		for (char c : astr.toCharArray()) {
			if (arr[c - 'a'] == 1) {
				return false;
			}
			arr[c - 'a'] = 1;
		}
		return true;
	}

	@Test
	public void isUnique_AllCharactersUnique_ShouldReturnTrue() {
		String input = "abcdefg";
		assertTrue(solution.isUnique(input));
	}

	@Test
	public void isUnique_SomeCharactersNotUnique_ShouldReturnFalse() {
		String input = "hello";
		assertFalse(solution.isUnique(input));
	}

	/**
	 * 判断两个字符串中的字符是否互异
	 *
	 * @param s1 字符串1
	 * @param s2 字符串2
	 *
	 * @return 是否互异
	 */
	public boolean checkPermutation(String s1, String s2) {
		int[] arr1 = new int[26];
		int[] arr2 = new int[26];

		for (char c : s1.toCharArray()) {
			if (arr1[c - 'a'] == 1) {
				return false;
			}
			arr1[c - 'a'] = 1;
		}
		for (char c : s2.toCharArray()) {
			if (arr2[c - 'a'] == 1) {
				return false;
			}
			arr2[c - 'a'] = 1;
		}
		for (int i = 0; i < 26; i++) {
			if (arr1[i] != arr2[i]) {
				return false;
			}
		}
		return true;
	}

	@Test
	public void checkPermutation_BothStringsEmpty_ReturnsTrue() {
		assertTrue(solution.checkPermutation("", ""));
	}

	@Test
	public void checkPermutation_FirstStringEmptySecondNonEmpty_ReturnsFalse() {
		assertFalse(solution.checkPermutation("", "abc"));
	}

	/**
	 * 替换空格
	 *
	 * @param str    字符串
	 * @param length 字符串长度
	 *
	 * @return 替换后的字符串
	 */
	public String replaceSpaces(String str, int length) {
		char[] chars = str.toCharArray();
		int index = chars.length - 1;
		for (int i = length - 1; i >= 0; i--) {
			if (chars[i] == ' ') {
				chars[index--] = '0';
				chars[index--] = '2';
				chars[index--] = '%';
			} else {
				chars[index--] = chars[i];
			}
		}
		return new String(chars, index + 1, chars.length - index - 1);
	}

	@Test
	public void replaceSpaces_WithSpaces_ShouldReplaceSpaces() {
		String input = "Mr John Smith    ";
		int length = 13;
		String expected = "Mr%20John%20Smith";
		String result = solution.replaceSpaces(input, length);
		assertEquals(expected, result);
	}

	/**
	 * 判断字符串是否为回文串
	 *
	 * @param s 字符串
	 *
	 * @return 是否为回文串
	 */
	public boolean canPermutePalindrome(String s) {
		if (s == null || s.length() == 0) {
			return true;
		}
		HashMap<Character, Integer> map = new HashMap<>(8);
		for (char ch : s.toCharArray()) {
			if (map.containsKey(ch)) {
				map.put(ch, map.get(ch) + 1);
			} else {
				map.put(ch, 1);
			}
		}
		int oddCount = 0;
		for (Map.Entry<Character, Integer> entry : map.entrySet()) {
			Integer integer = entry.getValue();
			if (integer % 2 == 1) {
				oddCount++;
				if (oddCount > 1) {
					return false;
				}
			}
		}
		return true;
	}

	@Test
	public void canPermutePalindrome_EvenLengthPalindrome_ReturnsTrue() {
		assertTrue(solution.canPermutePalindrome("aabbaa"));
	}

	@Test
	public void canPermutePalindrome_MultipleOddCharacters_ReturnsFalse() {
		assertFalse(solution.canPermutePalindrome("code"));
	}

	/**
	 * 一次编辑 三种编辑操作:插入一个英文字符、删除一个英文字符或者替换一个英文字符
	 *
	 * @param first  字符串1
	 * @param second 字符串2
	 *
	 * @return 只需要一次(或者零次)编辑。
	 */
	public boolean oneEditAway(String first, String second) {
		if (first == null || second == null) {
			return false;
		}
		int length = first.length();
		int length1 = second.length();
		if (length1 - length == 1) {
			// 插入一个英文字符
			return isOneEdit(second, first);
		} else if (length - length1 == 1) {
			// 删除一个英文字符
			return isOneEdit(first, second);
		} else if (length - length1 == 0) {
			// 替换一个英文字符
			int count = 0;
			for (int i = 0; i < length; i++) {
				if (first.charAt(i) != second.charAt(i)) {
					count++;
					if (count > 1) {
						return false;
					}
				}
			}
			return true;
		} else {
			return false;
		}
	}

	private boolean isOneEdit(String longer, String shorter) {
		int length = longer.length();
		int length1 = shorter.length();
		int index1 = 0;
		int index2 = 0;
		while (index1 < length1 && index2 < length) {
			if (shorter.charAt(index1) == longer.charAt(index2)) {
				index1++;
			}
			index2++;
			if (index2 - index1 > 1) {
				return false;
			}
		}
		return true;
	}

	@Test
	public void oneEditAway_NoEdit_ReturnsTrue() {
		assertTrue(solution.oneEditAway("abcd", "abcd"));
	}

	@Test
	public void oneEditAway_DifferentLengthsGreaterThanOne_ReturnsFalse() {
		assertFalse(solution.oneEditAway("abcd", "abxde"));
	}

	/**
	 * 旋转数组
	 *
	 * @param nums 数组
	 * @param k    旋转次数
	 */
	public void rotate(int[] nums, int k) {
		if (nums == null || nums.length == 0) {
			return;
		}
		int length = nums.length;
		k %= length;
		reverse(nums, 0, length - 1);
		reverse(nums, 0, k - 1);
		reverse(nums, k, length - 1);
	}

	private void reverse(int[] nums, int start, int end) {
		while (start < end) {
			int temp = nums[start];
			nums[start] = nums[end];
			nums[end] = temp;
			start++;
			end--;
		}
	}

	@Test
	public void rotate_WithValidInput_ShouldRotateArray() {
		int[] nums = {1, 2, 3, 4, 5, 6, 7};
		int k = 3;
		int[] expected = {5, 6, 7, 1, 2, 3, 4};
		solution.rotate(nums, k);
		assertArrayEquals(expected, nums);
	}

	/**
	 * 字符串压缩
	 *
	 * @param str 字符串
	 *
	 * @return 压缩后的字符串
	 */
	public String compressString(String str) {
		if (str == null || str.length() == 0) {
			return "";
		}
		StringBuilder stringBuilder = new StringBuilder();
		int slow = 0;
		int quick = 0;
		while (quick < str.length()) {
			char currentChar = str.charAt(quick);
			while (quick < str.length() && str.charAt(quick) == currentChar) {
				quick++;
			}
			stringBuilder.append(currentChar).append(quick - slow);
			slow = quick;
		}
		String result = stringBuilder.toString();
		return str.length() > result.length() ? result : str;
	}

	@Test
	public void compressString_MultipleDifferentCharacters_ReturnsCompressedString() {
		String result = solution.compressString("aabbbcccc");
		assertEquals("a2b3c4", result);
	}

	/**
	 * 旋转矩阵
	 *
	 * @param matrix 二维数组
	 */
	public void rotate(int[][] matrix) {

	}

}
