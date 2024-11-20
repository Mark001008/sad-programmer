package com.mark.core.pipeline;

/**
 * <p>描述: 幂等工具类 </p>
 * <p>创建时间: 2024/8/28 16:53 </p>
 *
 * @author Mark
 */
public class TairKeysConstantsTools {

	private TairKeysConstantsTools() {
		throw new IllegalStateException("Utility class");
	}

	public static String generateKey(String value, String appName, String simpleName) {
		return value + appName + simpleName;
	}

	public static String generateKey(String value, String appName, String simpleName, String idempotentKey) {
		return value + appName + simpleName + idempotentKey;
	}


}
