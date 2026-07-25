package com.ncwu.common.apis.iot_service;

import com.ncwu.common.domain.bo.ToAIBO;
import com.ncwu.common.domain.vo.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/** Contracts consumed by the prediction service from the IoT service. */
public interface IotDataService {
    Result<ToAIBO> getRecentWeekUsage();
    Result<Double> getQualityRate();
    Result<Double> getOfflineRate();
    Result<Map<String, Double>> getSwings();
    Result<Double> getSchoolUsage(int school, LocalDateTime start, LocalDateTime end);
    Result<Double> getUnNormalUsage(@Min(1) @Max(3) int campus);
    Result<Double> getWaterQualityScore(String deviceId);
    Result<Double> getHealthyScoreOfDevices();
    Result<Collection<String>> getOffLineList(int campus);
    Result<Double> getRate(int region, int campus);
    ResponseEntity<byte[]> getDeviceDatas(String deviceCode);
    Result<Map<Integer, Double>> getCampusRate();
}
