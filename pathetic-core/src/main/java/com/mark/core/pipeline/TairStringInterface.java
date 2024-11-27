package com.mark.core.pipeline;

import com.mark.core.pipeline.model.ExgetResult;
import com.mark.core.pipeline.model.ExsetParams;

import org.springframework.stereotype.Component;

/**
 * <p>描述: RDB </p>
 * <p>创建时间: 2024/8/28 16:52 </p>
 *
 * @author Mark
 */
@Component
public class TairStringInterface {

    public ExgetResult<String> exget(String key) {
        ExgetResult<String> result = new ExgetResult<>();
        result.setValue(key);
        return result;
    }

    public String exset(String key, String s, ExsetParams params) {
        return key + s + params.toString();
    }
}
