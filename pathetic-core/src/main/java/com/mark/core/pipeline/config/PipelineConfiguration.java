package com.mark.core.pipeline.config;

import com.mark.core.pipeline.SwitchConfigBiz;
import com.mark.core.pipeline.model.BaseContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <p>描述: 配置类</p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 * 实现动态编排pipeline
 *
 * @author Mark
 */
@Configuration
public class PipelineConfiguration<T extends BaseContext> {

    /**
     * key：StageConfig注解中的name value：实现了Stage接口的实例Bean
     */
    private final Map<String, Stage<T>> stageMap = new ConcurrentHashMap<>();

    @Autowired
    private ApplicationContext context;

    /**
     * 在构造方法后执行，确保所有依赖注入完，初始化pipeline的Map
     */
    @PostConstruct
    private void initStageMap() {
        // 拿到带有@StageConfig注解的所有bean
        Map<String, Object> beansWithAnnotation = context.getBeansWithAnnotation(StageConfig.class);

        // 遍历带有StageConfig注解的所有实例bean
        for (Object bean : beansWithAnnotation.values()) {
            if (bean instanceof Stage) {
                // 拿到注解
                StageConfig annotation = bean.getClass().getAnnotation(StageConfig.class);
                // 放入Map
                stageMap.put(annotation.name(), (Stage<T>) bean);
            }
        }
    }

    /**
     * 初始化pipeline的Map key：不同业务的pipeline类型标识 value：当前业务配置的执行链
     */
    @Bean(name = "pipelineMaps")
    public Map<String, List<Stage<T>>> initPipelineMaps(ApplicationContext applicationContext) {
        Map<String, List<Stage<T>>> pipelines = new ConcurrentHashMap<>();
        // 不同业务的pipeline执行链配置
        Map<String, List<String>> pipeLineBizExecuteChain = SwitchConfigBiz.PIPE_LINE_BIZ_EXECUTE_CHAIN;
        // 填充进去
        for (String bizIdentify : pipeLineBizExecuteChain.keySet()) {
            // 执行链BeanName列表
            List<String> executeChainBeanNameList = pipeLineBizExecuteChain.get(bizIdentify);
            // 映射到对应的bean上
            List<Stage<T>> executeChains = executeChainBeanNameList.stream().map(stageMap::get)
                    .collect(Collectors.toList());
            pipelines.put(bizIdentify, executeChains);
        }
        return pipelines;
    }

}