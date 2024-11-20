package com.mark.common.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;

/**
 * <p>描述: 文件工具类 </p>
 * <p>创建时间: 2024/9/18 9:13 </p>
 *
 * @author Mark
 */
public class FileUtil {

	private static final String ENCODING = "UTF-8";

	public static String readTxtFile(String filePath) throws IOException {
		StringBuilder stringBuffer = new StringBuilder();
		InputStreamReader readStream = null;
		try {
			File file = new File(filePath);
			if (file.isFile() && file.exists()) {
				readStream = new InputStreamReader(Files.newInputStream(file.toPath()), ENCODING);
				BufferedReader bufferedReader = new BufferedReader(readStream);
				String lineTxt;
				while ((lineTxt = bufferedReader.readLine()) != null) {
					stringBuffer.append(lineTxt);
					stringBuffer.append(",");
				}
				stringBuffer.deleteCharAt(stringBuffer.length() - 1);
				readStream.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return stringBuffer.toString();
	}

	@Test
	public void testReadTxtFile() {
		String filePath = "D:\\file.txt";
		try {
			readTxtFile(filePath);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
