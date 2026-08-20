package com.ncwu.predictionservice.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ncwu.predictionservice.task.entity.ScheduledTaskExecutionEntity;
import com.ncwu.predictionservice.task.mapper.ScheduledTaskExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("memory")
@RequiredArgsConstructor
public class ScheduledTaskExecutionRepository {

    private final ScheduledTaskExecutionMapper mapper;

    public UUID start(UUID taskId, String userId, LocalDateTime startedAt) {
        UUID executionId = UUID.randomUUID();
        ScheduledTaskExecutionEntity entity = new ScheduledTaskExecutionEntity();
        entity.setId(executionId);
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setStatus("RUNNING");
        entity.setStartedAt(startedAt);
        mapper.insert(entity);
        return executionId;
    }

    public void finish(UUID executionId, String status, String result, LocalDateTime finishedAt) {
        ScheduledTaskExecutionEntity current = mapper.selectById(executionId);
        if (current == null) return;
        Long durationMs = current.getStartedAt() == null ? null
                : Duration.between(current.getStartedAt(), finishedAt).toMillis();
        mapper.update(null, new LambdaUpdateWrapper<ScheduledTaskExecutionEntity>()
                .eq(ScheduledTaskExecutionEntity::getId, executionId)
                .set(ScheduledTaskExecutionEntity::getStatus, status)
                .set(ScheduledTaskExecutionEntity::getFinishedAt, finishedAt)
                .set(ScheduledTaskExecutionEntity::getDurationMs, durationMs)
                .set(ScheduledTaskExecutionEntity::getResult, truncate(result)));
    }

    public List<ScheduledTaskExecution> findByTask(UUID taskId, String userId) {
        return mapper.selectList(new LambdaQueryWrapper<ScheduledTaskExecutionEntity>()
                        .eq(ScheduledTaskExecutionEntity::getTaskId, taskId)
                        .eq(ScheduledTaskExecutionEntity::getUserId, userId)
                        .orderByDesc(ScheduledTaskExecutionEntity::getStartedAt))
                .stream().map(this::toExecution).toList();
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 20000 ? value : value.substring(0, 20000) + "…";
    }

    private ScheduledTaskExecution toExecution(ScheduledTaskExecutionEntity entity) {
        return new ScheduledTaskExecution(entity.getId().toString(), entity.getTaskId().toString(),
                entity.getStatus(), entity.getStartedAt(), entity.getFinishedAt(), entity.getDurationMs(),
                entity.getResult());
    }
}


