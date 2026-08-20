package com.ncwu.predictionservice.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ncwu.predictionservice.task.entity.ScheduledTaskEntity;
import com.ncwu.predictionservice.task.mapper.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
@RequiredArgsConstructor
public class ScheduledTaskRepository {

    private final ScheduledTaskMapper mapper;

    public ScheduledTask create(String userId, String conversationId, String taskName,
                                String cronExpression, String instruction, String timeZone,
                                LocalDateTime nextRunAt) {
        LocalDateTime now = LocalDateTime.now();
        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setConversationId(UUID.fromString(conversationId));
        entity.setTaskName(taskName);
        entity.setCronExpression(cronExpression);
        entity.setInstruction(instruction);
        entity.setTimeZone(timeZone);
        entity.setStatus("ACTIVE");
        entity.setNextRunAt(nextRunAt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toTask(entity);
    }

    public List<ScheduledTask> findOwned(String userId) {
        return mapper.selectList(new LambdaQueryWrapper<ScheduledTaskEntity>()
                        .eq(ScheduledTaskEntity::getUserId, userId)
                        .isNull(ScheduledTaskEntity::getDeletedAt)
                        .orderByAsc(ScheduledTaskEntity::getNextRunAt))
                .stream().map(this::toTask).toList();
    }

    public Optional<ScheduledTask> findOwned(String taskId, String userId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ScheduledTaskEntity>()
                        .eq(ScheduledTaskEntity::getId, UUID.fromString(taskId))
                        .eq(ScheduledTaskEntity::getUserId, userId)
                        .isNull(ScheduledTaskEntity::getDeletedAt)))
                .map(this::toTask);
    }

    public List<ScheduledTask> findDue(LocalDateTime now) {
        return mapper.selectList(new LambdaQueryWrapper<ScheduledTaskEntity>()
                        .eq(ScheduledTaskEntity::getStatus, "ACTIVE")
                        .le(ScheduledTaskEntity::getNextRunAt, now)
                        .isNull(ScheduledTaskEntity::getDeletedAt)
                        .orderByAsc(ScheduledTaskEntity::getNextRunAt))
                .stream().map(this::toTask).toList();
    }

    /** 数据库条件更新用于避免多实例部署时同一个任务被重复领取。 */
    public boolean claim(UUID taskId, LocalDateTime now) {
        return mapper.update(null, new LambdaUpdateWrapper<ScheduledTaskEntity>()
                .eq(ScheduledTaskEntity::getId, taskId)
                .eq(ScheduledTaskEntity::getStatus, "ACTIVE")
                .le(ScheduledTaskEntity::getNextRunAt, now)
                .isNull(ScheduledTaskEntity::getDeletedAt)
                .set(ScheduledTaskEntity::getStatus, "RUNNING")
                .set(ScheduledTaskEntity::getUpdatedAt, now)) > 0;
    }

    public void markFinished(UUID taskId, String status, String lastStatus, String result,
                             LocalDateTime lastRunAt, LocalDateTime nextRunAt) {
        mapper.update(null, new LambdaUpdateWrapper<ScheduledTaskEntity>()
                .eq(ScheduledTaskEntity::getId, taskId)
                .set(ScheduledTaskEntity::getStatus, status)
                .set(ScheduledTaskEntity::getLastStatus, lastStatus)
                .set(ScheduledTaskEntity::getLastResult, truncate(result))
                .set(ScheduledTaskEntity::getLastRunAt, lastRunAt)
                .set(ScheduledTaskEntity::getNextRunAt, nextRunAt)
                .set(ScheduledTaskEntity::getUpdatedAt, lastRunAt));
    }

    public void softDelete(String taskId, String userId) {
        mapper.update(null, new LambdaUpdateWrapper<ScheduledTaskEntity>()
                .eq(ScheduledTaskEntity::getId, UUID.fromString(taskId))
                .eq(ScheduledTaskEntity::getUserId, userId)
                .isNull(ScheduledTaskEntity::getDeletedAt)
                .set(ScheduledTaskEntity::getStatus, "DELETED")
                .set(ScheduledTaskEntity::getDeletedAt, LocalDateTime.now())
                .set(ScheduledTaskEntity::getUpdatedAt, LocalDateTime.now()));
    }

    public void softDeleteByConversation(String conversationId, String userId) {
        mapper.update(null, new LambdaUpdateWrapper<ScheduledTaskEntity>()
                .eq(ScheduledTaskEntity::getConversationId, UUID.fromString(conversationId))
                .eq(ScheduledTaskEntity::getUserId, userId)
                .isNull(ScheduledTaskEntity::getDeletedAt)
                .set(ScheduledTaskEntity::getStatus, "DELETED")
                .set(ScheduledTaskEntity::getDeletedAt, LocalDateTime.now())
                .set(ScheduledTaskEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 10000 ? value : value.substring(0, 10000) + "…";
    }

    private ScheduledTask toTask(ScheduledTaskEntity entity) {
        return new ScheduledTask(entity.getId().toString(), entity.getUserId(), entity.getConversationId().toString(),
                entity.getTaskName(), entity.getCronExpression(), entity.getInstruction(), entity.getTimeZone(),
                entity.getStatus(), entity.getNextRunAt(), entity.getLastRunAt(), entity.getLastStatus(),
                entity.getLastResult(), entity.getCreatedAt());
    }
}


