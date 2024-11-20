package com.mark.controller;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>描述: http </p>
 * <p>创建时间: 2024/9/14 17:17 </p>
 *
 * @author Mark
 */
@RestController
@RequestMapping("/app")
public class AppController {

	@Resource
	private FlowExecutor flowExecutor;

	@GetMapping("/hello")
	public String sayHello() {
		return "hello world";
	}


	@GetMapping("/flow.test")
	public void testConfig() {
		LiteflowResponse response = flowExecutor.execute2Resp("chain1", "arg");
		System.out.println(response.toString());
	}
}
