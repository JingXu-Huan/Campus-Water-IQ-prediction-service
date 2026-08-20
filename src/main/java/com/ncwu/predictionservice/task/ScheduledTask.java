package com.ncwu.predictionservice.task;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

public record ScheduledTask(
        String id,
        @JsonIgnore
        String userId,
        String conversationId,
        String taskName,
        String cronExpression,
        String instruction,
        String timeZone,
        String status,
        LocalDateTime nextRunAt,
        LocalDateTime lastRunAt,
        String lastStatus,
        String lastResult,
        LocalDateTime createdAt) {
}


