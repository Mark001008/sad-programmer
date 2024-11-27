package com.mark.service;

/**
 * <p>描述: 流程编排引擎 </p>
 * <p>创建时间: 2024/9/14 17:22 </p>
 *
 * @author Mark
 */
public interface FlowService extends BasicService {

    /**
     * 执行流程编排
     *
     * @param bizCode 业务编码
     */
    void execute(String bizCode);
}
