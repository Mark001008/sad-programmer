package com.mark.core.pipeline.config;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * <p>描述: Stage配置 </p>
 * <p>创建时间: 2024/9/24 16:23 </p>
 *
 * @author Mark
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface StageConfig {

    /**
     * 阶段名称
     */
    String name();
}