package com.mark.service.impl;

import com.mark.service.FlowService;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;

import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>描述:  </p>
 * <p>创建时间: 2024/9/14 17:23 </p>
 *
 * @author Mark
 */
@Service
public abstract class AbstractFlowService implements FlowService {

	@Resource
	private FlowExecutor flowExecutor;

	@Override
	public void execute(String bizCode) {

	}

	public void testConfig() {
		LiteflowResponse response = flowExecutor.execute2Resp("chain1", "arg");
	}
}
