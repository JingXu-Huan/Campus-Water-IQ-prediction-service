package com.ncwu.common.apis;

import com.ncwu.common.domain.bo.ToAIBO;
import com.ncwu.common.domain.vo.Result;

/** Local contract used to supply recent water-usage data. */
public interface IoTDataServiceApi {
    Result<ToAIBO> getRecentWeekUsage();
}
