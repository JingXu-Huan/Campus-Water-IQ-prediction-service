package com.ncwu.common.apis.iot_device;

import com.ncwu.common.domain.vo.Result;
import java.util.List;
import java.util.Map;

/** Contract for querying device status through Dubbo. */
public interface IotDeviceApi {
    Result<Map<String, String>> checkDeviceStatus(List<String> ids);
}
