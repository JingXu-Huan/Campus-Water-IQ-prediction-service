package com.ncwu.predictionservice.task;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("memory")
@RequiredArgsConstructor
public class ScheduledTaskTools {

    private final ScheduledTaskService scheduledTaskService;

    @Tool("""
            创建一个周期性校园水务定时任务。仅当用户明确要求安排、创建、设置或每天/每周定时执行某项检查时调用。
            必须从用户当前会话理解任务名称和执行指令。
            cronExpression 使用 Spring 六段 Cron 格式：秒 分 时 日 月 周，例如每天早上 8 点是 0 0 8 * * *，
            每周一早上 8 点是 0 0 8 * * MON。不要使用 Linux 五段 Cron。
            taskName 是简短中文任务名；instruction 是到点交给 Water Agent 执行的完整检查指令；
            timeZone 默认 Asia/Shanghai。不要在用户只是询问建议时调用。""")
    public String scheduleRecurringTask(
            @P("简短中文任务名") String taskName,
            @P("Spring 六段 Cron 表达式，例如每天 8 点为 0 0 8 * * *") String cronExpression,
            @P("到点后交给 Water Agent 执行的完整校园水务检查指令") String instruction,
            @P(name = "timeZone", description = "IANA 时区，默认 Asia/Shanghai", required = false,
                    defaultValue = "Asia/Shanghai") String timeZone,
            @ToolMemoryId String conversationId,
            InvocationParameters parameters) {
        Object userIdValue = parameters == null ? null : parameters.get("userId");
        String userId = userIdValue == null ? null : userIdValue.toString();
        if (userId == null || userId.isBlank()) {
            return "无法创建定时任务：当前会话缺少用户身份。";
        }
        try {
            ScheduledTask task = scheduledTaskService.create(userId, conversationId, taskName,
                    cronExpression, instruction, timeZone);
            return "已创建定时任务“%s”，下一次执行时间为 %s，任务指令：%s"
                    .formatted(task.taskName(), task.nextRunAt(), task.instruction());
        } catch (IllegalArgumentException exception) {
            return "定时任务创建失败：" + exception.getMessage();
        }
    }
}


