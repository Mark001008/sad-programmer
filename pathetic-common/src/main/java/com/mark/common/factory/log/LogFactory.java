package com.mark.common.factory.log;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.ttl.TransmittableThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>描述: 日志工厂 </p>
 * <p>创建时间: 2024/11/14 9:43 </p>
 *
 * @author Mark
 */
public class LogFactory {

	private static final Map<String, LFLog> LOG_MAP = new HashMap<>();

	private static final TransmittableThreadLocal<String> REQUEST_ID_TL = new TransmittableThreadLocal<>();

	public static LFLog getLogger(Class<?> clazz) {
		if (LOG_MAP.containsKey(clazz.getName())) {
			return LOG_MAP.get(clazz.getName());
		} else {
			Logger log = LoggerFactory.getLogger(clazz.getName());
			LFLog lfLog = new LFLog(log);
			LOG_MAP.put(clazz.getName(), lfLog);
			return lfLog;
		}
	}

	public static void setRequestId(String requestId) {
		REQUEST_ID_TL.set(requestId);
	}

	public static String getRequestId() {
		return REQUEST_ID_TL.get();
	}

	public static void removeRequestId() {
		REQUEST_ID_TL.remove();
	}
}
