package com.mark.core.pipeline.model;

import lombok.Data;

/**
 * <p>描述:  </p>
 * <p>创建时间: 2024/8/28 17:25 </p>
 *
 * @author Mark
 */
@Data
public class Result<T> {

    private T data;

    public static Result<Boolean> success(boolean success) {
        return new Result<>();
    }
}
