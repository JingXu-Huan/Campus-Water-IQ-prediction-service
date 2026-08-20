package com.ncwu.predictionservice.task;

import com.ncwu.predictionservice.agent.AgentAnswer;
import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Profile("memory")
@RequiredArgsConstructor
public class ScheduledTaskService {

    private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ScheduledTaskRepository repository;
    private final ScheduledTaskExecutionRepository executionRepository;
    private final ConversationRepository conversationRepository;
    /** 延迟获取，避免 WaterAgent 注册 ScheduledTaskTools 时形成循环依赖。 */
    private final ObjectProvider<WaterAgent> waterAgentProvider;
    private final RedissonClient redissonClient;

    public ScheduledTask create(String userId, String conversationId, String taskName,
                                String cronExpression, String instruction, String timeZone) {
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            throw new IllegalArgumentException("只能为当前用户自己的会话创建定时任务");
        }
        String normalizedName = requireText(taskName, "任务名称不能为空");
        String normalizedInstruction = requireText(instruction, "任务指令不能为空");
        String normalizedCron = requireText(cronExpression, "Cron 表达式不能为空");
        String normalizedZone = normalizeZone(timeZone);
        CronExpression cron = parseCron(normalizedCron);
        ZoneId zoneId = ZoneId.of(normalizedZone);
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalDateTime nextRunAt = nextRun(cron, now);
        return repository.create(userId, conversationId, normalizedName, normalizedCron,
                normalizedInstruction, normalizedZone, nextRunAt);
    }

    public List<ScheduledTask> list(String userId) {
        return repository.findOwned(userId);
    }

    public boolean delete(String taskId, String userId) {
        Optional<ScheduledTask> task = repository.findOwned(taskId, userId);
        if (task.isEmpty()) return false;
        repository.softDelete(taskId, userId);
        return true;
    }

    public List<ScheduledTaskExecution> history(String taskId, String userId) {
        Optional<ScheduledTask> task = repository.findOwned(taskId, userId);
        if (task.isEmpty()) return List.of();
        return executionRepository.findByTask(UUID.fromString(taskId), userId);
    }

    /** Spring 负责触发轮询，任务本身由 LangChain4j WaterAgent 执行。 */
    @Scheduled(fixedDelayString = "${agent.scheduler.poll-interval-ms:30000}")
    public void pollDueTasks() {
        LocalDateTime now = LocalDateTime.now();
        for (ScheduledTask task : repository.findDue(now)) {
            if (repository.claim(UUID.fromString(task.id()), now)) {
                execute(task);
            }
        }
    }

    private void execute(ScheduledTask task) {
        UUID taskId = UUID.fromString(task.id());
        LocalDateTime runAt = LocalDateTime.now();
        UUID executionId = null;
        try {
            executionId = executionRepository.start(taskId, task.userId(), runAt);
            RLock lock = redissonClient.getLock("agent:conversation:" + task.conversationId());
            if (!lock.tryLock(5, 90, TimeUnit.SECONDS)) {
                throw new IllegalStateException("会话正在进行其他 Agent 调用");
            }
            String answer;
            try {
                AgentAnswer result = waterAgentProvider.getObject().chat(task.conversationId(),
                        "这是一个已授权的定时任务，请直接执行以下检查并给出结果，不要创建新的定时任务：\n"
                                + task.instruction(),
                        InvocationParameters.from(Map.of("userId", task.userId())));
                answer = result == null || result.answer() == null ? "Agent 未返回有效结果" : result.answer().trim();
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
            conversationRepository.appendMessage(task.conversationId(), "assistant",
                    "【定时任务：" + task.taskName() + "，执行时间 " + runAt.format(DISPLAY_TIME) + "】\n" + answer);
            LocalDateTime finishedAt = LocalDateTime.now();
            executionRepository.finish(executionId, "SUCCESS", answer, finishedAt);
            repository.markFinished(taskId, "ACTIVE", "SUCCESS", answer,
                    runAt, nextRun(task.cronExpression(), task.timeZone(), runAt));
            log.info("定时任务执行成功: taskId={}, taskName={}", task.id(), task.taskName());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "任务执行失败" : exception.getMessage();
            if (executionId != null) {
                executionRepository.finish(executionId, "FAILED", message, LocalDateTime.now());
            }
            repository.markFinished(taskId, "ACTIVE", "FAILED", message,
                    runAt, nextRun(task.cronExpression(), task.timeZone(), runAt));
            log.error("定时任务执行失败: taskId={}, taskName={}", task.id(), task.taskName(), exception);
        }
    }

    private LocalDateTime nextRun(String cronExpression, String timeZone, LocalDateTime from) {
        return nextRun(parseCron(cronExpression), from, ZoneId.of(normalizeZone(timeZone)));
    }

    private LocalDateTime nextRun(CronExpression cron, LocalDateTime from) {
        return nextRun(cron, from, ZoneId.systemDefault());
    }

    private LocalDateTime nextRun(CronExpression cron, LocalDateTime from, ZoneId zoneId) {
        ZonedDateTime next = cron.next(from.atZone(zoneId));
        if (next == null) throw new IllegalArgumentException("Cron 表达式没有下一次执行时间");
        return next.toLocalDateTime();
    }

    private CronExpression parseCron(String expression) {
        if (!CronExpression.isValidExpression(expression)) {
            throw new IllegalArgumentException("Cron 表达式无效，应使用 Spring 六段格式，例如：0 0 8 * * *");
        }
        return CronExpression.parse(expression);
    }

    private String normalizeZone(String timeZone) {
        String value = timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone.trim();
        try {
            ZoneId.of(value);
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("不支持的时区：" + value, exception);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}


