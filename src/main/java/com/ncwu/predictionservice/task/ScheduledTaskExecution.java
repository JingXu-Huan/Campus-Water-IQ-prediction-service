package com.ncwu.predictionservice.task;

import java.time.LocalDateTime;

public record ScheduledTaskExecution(
        String id,
        String taskId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String result) {
}


