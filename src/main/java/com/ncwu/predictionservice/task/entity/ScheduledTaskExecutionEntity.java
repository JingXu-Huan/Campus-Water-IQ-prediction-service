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
@TableName("agent_scheduled_task_execution")
public class ScheduledTaskExecutionEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID taskId;
    private String userId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String result;
}


