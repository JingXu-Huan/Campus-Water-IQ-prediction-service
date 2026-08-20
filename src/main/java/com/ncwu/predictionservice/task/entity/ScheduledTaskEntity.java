package com.ncwu.predictionservice.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@TableName("agent_scheduled_task")
public class ScheduledTaskEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private String userId;
    private UUID conversationId;
    private String taskName;
    private String cronExpression;
    private String instruction;
    private String timeZone;
    private String status;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String lastStatus;
    private String lastResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}


