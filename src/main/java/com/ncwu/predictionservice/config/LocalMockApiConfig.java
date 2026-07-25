package com.ncwu.predictionservice.config;

import com.ncwu.common.apis.IoTDataServiceApi;
import com.ncwu.common.apis.iot_device.IotDeviceApi;
import com.ncwu.common.apis.iot_service.IotDataService;
import com.ncwu.common.apis.repair_service.DeviceReservationServiceApi;
import com.ncwu.common.domain.bo.ToAIBO;
import com.ncwu.common.domain.dto.UserReportDTO;
import com.ncwu.common.domain.vo.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Local replacements for APIs normally supplied by the other microservices.
 * They keep this extracted module runnable without a registry or providers.
 */
@Configuration
public class LocalMockApiConfig {
    private final int minDelayMs;
    private final int maxDelayMs;

    public LocalMockApiConfig(
            @Value("${mock.api.min-delay-ms:500}") int minDelayMs,
            @Value("${mock.api.max-delay-ms:1200}") int maxDelayMs) {
        this.minDelayMs = Math.max(0, minDelayMs);
        this.maxDelayMs = Math.max(this.minDelayMs, maxDelayMs);
    }

    @Bean
    IoTDataServiceApi mockIoTDataServiceApi() {
        return () -> {
            delay();
            return Result.ok(new ToAIBO(
                    List.of(1860.2, 1914.8, 1886.1, 1952.4, 2011.7, 1978.5, 2042.3),
                    List.of(2310.6, 2276.9, 2398.4, 2412.8, 2361.2, 2480.5, 2526.7),
                    List.of(1644.1, 1682.6, 1715.9, 1698.3, 1752.8, 1791.4, 1820.6)
            ));
        };
    }

    @Bean
    IotDataService mockIotDataService(IoTDataServiceApi recentUsageApi) {
        return new IotDataService() {
            @Override public Result<ToAIBO> getRecentWeekUsage() { return recentUsageApi.getRecentWeekUsage(); }
            @Override public Result<Double> getQualityRate() { delay(); return Result.ok(0.982); }
            @Override public Result<Double> getOfflineRate() { delay(); return Result.ok(0.018); }
            @Override public Result<Map<String, Double>> getSwings() {
                delay();
                return Result.ok(Map.of("school_1", 0.12, "school_2", 0.09, "school_3", 0.15));
            }
            @Override public Result<Double> getSchoolUsage(int school, LocalDateTime start, LocalDateTime end) {
                delay();
                return Result.ok(school * 1265.4);
            }
            @Override public Result<Double> getUnNormalUsage(int campus) { delay(); return Result.ok(campus * 23.6); }
            @Override public Result<Double> getWaterQualityScore(String deviceId) { delay(); return Result.ok(92.5); }
            @Override public Result<Double> getHealthyScoreOfDevices() { delay(); return Result.ok(96.8); }
            @Override public Result<Collection<String>> getOffLineList(int campus) {
                delay();
                return Result.ok(List.of("10" + campus + "0201001", "20" + campus + "0302001"));
            }
            @Override public Result<Double> getRate(int region, int campus) { delay(); return Result.ok(0.33); }
            @Override public ResponseEntity<byte[]> getDeviceDatas(String deviceCode) {
                delay();
                String csv = "deviceCode,timestamp,usage\\n" + deviceCode + "," + LocalDateTime.now() + ",42.8\\n";
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                        .body(csv.getBytes(StandardCharsets.UTF_8));
            }
            @Override public Result<Map<Integer, Double>> getCampusRate() {
                delay();
                return Result.ok(Map.of(1, 0.34, 2, 0.41, 3, 0.25));
            }
        };
    }

    @Bean
    IotDeviceApi mockIotDeviceApi() {
        return ids -> {
            delay();
            Map<String, String> statuses = new LinkedHashMap<>();
            ids.forEach(id -> statuses.put(id, "online,true"));
            return Result.ok(statuses);
        };
    }

    @Bean
    DeviceReservationServiceApi mockDeviceReservationServiceApi() {
        return report -> {
            delay();
            return Result.ok(Boolean.TRUE);
        };
    }

    private void delay() {
        int delay = ThreadLocalRandom.current().nextInt(minDelayMs, maxDelayMs + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
