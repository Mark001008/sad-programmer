package com.mark.core.pipeline.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>描述: 结果 </p>
 * <p>创建时间: 2024/8/28 16:32 </p>
 *
 * @author Mark
 */
@Getter
@AllArgsConstructor
public class StageProcessedResultEnum {

    public static final StageProcessedResultEnum IDEMPOTENT_FAIL = null;

    public static final StageProcessedResultEnum WRITE_USER_RIGHTS_RECORD_DB_FAIL = null;

    public static final StageProcessedResultEnum SUCCESS = null;

    public static final StageProcessedResultEnum WRITE_USER_RIGHTS_FLOW_DB_FAIL = null;

    public static StageProcessedResultEnum SKIP_ALL = null;


    public boolean isSuccess() {
        return false;
    }
}
