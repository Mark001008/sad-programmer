package com.mark.appconfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>描述: 线程池 </p>
 * <p>创建时间: 2024/5/13 15:43 </p>
 *
 * @author Mark
 */
@Configuration
public class ThreadPoolConfig {

	@Bean(name = "myExecutor")
	public ThreadPoolExecutor myExecutor() {
		return new ThreadPoolExecutor(5, 10, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
				new ThreadFactoryBuilder().setNameFormat("Apply_Pass_Thread-%d").build(),
				new ThreadPoolExecutor.CallerRunsPolicy());
	}
}
