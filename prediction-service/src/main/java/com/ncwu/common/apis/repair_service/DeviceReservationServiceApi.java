package com.ncwu.common.apis.repair_service;

import com.ncwu.common.domain.dto.UserReportDTO;
import com.ncwu.common.domain.vo.Result;

/** Contract for submitting repair reports through Dubbo. */
public interface DeviceReservationServiceApi {
    Result<Boolean> addAReport(UserReportDTO userReportDTO);
}
