package com.ncwu.predictionservice.controller;

import com.ncwu.common.apis.IoTDataServiceApi;
import com.ncwu.common.apis.iot_service.IotDataService;
import com.ncwu.common.domain.bo.ToAIBO;
import com.ncwu.common.domain.vo.Result;
import com.ncwu.predictionservice.agent.WaterStreamingAgent;
import com.ncwu.predictionservice.conversation.AgentConversation;
import com.ncwu.predictionservice.conversation.AgentMessage;
import com.ncwu.predictionservice.domain.vo.UsageVO;
import com.ncwu.predictionservice.service.AiService;
import com.ncwu.predictionservice.task.ScheduledTask;
import com.ncwu.predictionservice.task.ScheduledTaskExecution;
import com.ncwu.predictionservice.task.ScheduledTaskService;
import com.ncwu.predictionservice.trace.AgentStreamTraceCollector;
import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai")
public class AIServiceController {

    private final AiService aiService;
    private final IoTDataServiceApi ioTDataServiceApi;
    private final IotDataService iotDataService;
    private final AgentTraceContext agentTraceContext;
    private final WaterStreamingAgent waterStreamingAgent;
    private final RedissonClient redissonClient;
    private final ObjectProvider<ScheduledTaskService> scheduledTaskServiceProvider;

    @PostMapping("/predictTomorrowWaterUsage")
    public Result<UsageVO> predictTomorrowWaterUsage(@Min(1) @Max(3) @RequestParam int campus) {
        try {
            Result<ToAIBO> response = ioTDataServiceApi.getRecentWeekUsage();
            if (response == null || response.getData() == null) {
                return Result.fail(null, "获取用水数据失败");
            }
            List<Double> usageData = switch (campus) {
                case 1 -> response.getData().getHY();
                case 2 -> response.getData().getLH();
                case 3 -> response.getData().getJH();
                default -> List.of();
            };
            if (usageData == null || usageData.isEmpty()) {
                return Result.fail(null, "暂无用水数据");
            }
            return aiService.predictTomorrowWaterUsage(usageData, campus);
        } catch (Exception error) {
            log.error("调用 IoT-service 失败", error);
            return Result.fail(null, "获取用水数据失败: " + error.getMessage());
        }
    }

    @PostMapping("/suggestionOfWater")
    public Result<String> suggestionOfWater(double score, double ph, double ch, double th) {
        return aiService.suggestionOfWater((int) score, ph, ch, th);
    }

    @PostMapping("/suggestions")
    public Result<String> giveSuggestions() {
        return aiService.suggestionOfWaterUsage();
    }

    @GetMapping("/suggestionOfDevice")
    public Result<String> suggestionOfDevice() {
        Result<Double> qualityRate = iotDataService.getQualityRate();
        return aiService.suggestionOfDevice(qualityRate.getData());
    }

    @PostMapping("/chatWithAgent")
    public Result<com.ncwu.predictionservice.trace.AgentChatResponse> chat(
            @RequestParam String input,
            @RequestParam(required = false) String conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        try (AgentTraceContext.ActiveTrace activeTrace = agentTraceContext.begin()) {
            Result<com.ncwu.predictionservice.conversation.AgentChatResponse> result =
                    aiService.chatWithAgent(conversationId, userId, input);
            if (result.getData() == null) {
                return Result.fail(null, result.getMessage());
            }
            return Result.ok(new com.ncwu.predictionservice.trace.AgentChatResponse(
                    result.getData().conversationId(), result.getData().answer(), activeTrace.snapshot()));
        }
    }

    /**
     * 以 SSE（Server-Sent Events）方式流式返回 Agent 的回答。
     *
     * <p>一次请求的大致流程是：
     * <ol>
     *     <li>创建一个不会自动超时的 SSE 连接；</li>
     *     <li>根据会话 ID 和用户 ID 找到或创建会话；</li>
     *     <li>给当前会话加分布式锁，避免同一个会话同时生成多条回答；</li>
     *     <li>启动 Agent，并把模型输出、检索结果和工具调用过程实时推送给前端；</li>
     *     <li>生成成功后保存完整回答，发送 done 事件并关闭连接。</li>
     * </ol>
     *
     * <p>前端可以根据事件名区分消息类型：conversation、delta、trace、done 和 error。
     *
     * @param input 用户本次发送的问题
     * @param conversationId 已有会话的 ID；为空时由服务层解析或创建会话
     * @param userId 当前用户 ID，从请求头 X-User-Id 获取；未传时使用 anonymous
     * @return SSE 响应对象，后续内容通过该对象持续推送，而不是一次性返回
     */
    @PostMapping(value = "/chatWithAgent/stream", produces = "text/event-stream")
    public SseEmitter streamChat(@RequestParam String input,
                                 @RequestParam(required = false) String conversationId,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        // 0L 表示不设置 Spring MVC 的默认超时时间，连接由业务流程主动结束。
        SseEmitter emitter = new SseEmitter(0L);

        // 用于收集检索来源和工具调用信息，并在回答生成期间持续推送给前端。
        AgentStreamTraceCollector traceCollector = new AgentStreamTraceCollector();

        // 校验会话归属；conversationId 为空时，服务层会负责确定要使用的会话。
        Result<AgentConversation> conversationResult = aiService.resolveConversation(conversationId, userId);
        if (conversationResult.getData() == null) {
            // 会话不存在或不属于当前用户：通过 SSE error 事件通知前端，然后结束连接。
            send(emitter, "error", conversationResult.getMessage());
            emitter.complete();
            return emitter;
        }

        // memoryId 是 Agent 记忆和会话记录使用的实际会话 ID。
        String memoryId = conversationResult.getData().id();

        // 同一会话只能同时进行一次回答生成，锁名按会话 ID 隔离。
        RLock lock = redissonClient.getLock("agent:conversation:" + memoryId);

        // 解锁时需要使用加锁线程 ID；流式回调可能在其他线程执行。
        long lockOwnerThreadId = Thread.currentThread().threadId();

        // 防止 complete、error、catch 等多个回调重复保存回答或释放锁。
        AtomicBoolean completed = new AtomicBoolean();

        // SSE 只发送回答增量，这里同时拼接出完整回答，完成后保存到数据库/记忆中。
        StringBuffer answer = new StringBuffer();
        try {
            // 最多等待 5 秒获取锁，锁租约为 90 秒；获取失败说明该会话正在生成回答。
            if (!lock.tryLock(5, 90, TimeUnit.SECONDS)) {
                send(emitter, "error", "该会话正在生成回复，请稍后重试");
                emitter.complete();
                return emitter;
            }

            // 先记录用户消息，确保 Agent 生成回答时能读取到本轮输入。
            Result<Void> recordResult = aiService.recordConversationUserMessage(memoryId, userId, input);
            if (!"200".equals(recordResult.getCode())) {
                send(emitter, "error", recordResult.getMessage());
                emitter.complete();
                unlock(lock, lockOwnerThreadId);
                return emitter;
            }

            // 告诉前端本次实际使用的会话 ID，后续请求可携带它继续对话。
            send(emitter, "conversation", memoryId);

            // 启动 Agent 的流式调用；真正的模型输出会在下面的回调中陆续到达。
            TokenStream tokenStream = waterStreamingAgent.chat(memoryId, input,
                    InvocationParameters.from(Map.of("userId", userId)));
            tokenStream
                    .onPartialResponse(token -> {
                        // 每收到一个回答片段，就立即推送 delta，同时拼接完整回答。
                        answer.append(token);
                        send(emitter, "delta", token);
                    })
                    .onRetrieved(contents -> {
                        // Agent 检索到知识库内容后，推送最新的检索轨迹。
                        traceCollector.recordRetrievedContent(contents);
                        // 逐步推送完整快照，使前端能在模型回答完成前展示检索来源。
                        send(emitter, "trace", traceCollector.snapshot());
                    })
                    .onToolExecuted(execution -> {
                        // Agent 执行工具后，推送最新的工具调用轨迹。
                        traceCollector.recordToolExecution(execution);
                        send(emitter, "trace", traceCollector.snapshot());
                    })
                    .onCompleteResponse(ignored -> {
                        // 模型正常结束：只允许第一个完成回调保存回答并释放锁。
                        if (completed.compareAndSet(false, true)) {
                            try {
                                aiService.completeConversationTurn(memoryId, answer.toString());
                            } finally {
                                unlock(lock, lockOwnerThreadId);
                            }
                        }
                        // 发送最终轨迹和 done 事件，通知前端可以结束本次流式处理。
                        send(emitter, "trace", traceCollector.snapshot());
                        send(emitter, "done", "");
                        emitter.complete();
                    })
                    .onError(error -> {
                        // 模型调用失败时释放锁，并通过 error 事件通知前端。
                        if (completed.compareAndSet(false, true)) {
                            unlock(lock, lockOwnerThreadId);
                        }
                        log.error("流式 Agent 调用失败", error);
                        send(emitter, "error", error.getMessage() == null ? "调用模型失败，请稍后重试" : error.getMessage());
                        emitter.completeWithError(error);
                    })
                    .start();
        } catch (Exception error) {
            // 启动流式调用阶段就发生异常时，也要释放锁并结束 SSE 连接。
            if (completed.compareAndSet(false, true)) {
                unlock(lock, lockOwnerThreadId);
            }
            log.error("启动流式 Agent 调用失败", error);
            send(emitter, "error", error.getMessage() == null ? "调用模型失败，请稍后重试" : error.getMessage());
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private void unlock(RLock lock, long ownerThreadId) {
        lock.unlockAsync(ownerThreadId);
    }

    @PostMapping("/conversations")
    public Result<AgentConversation> createConversation(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return aiService.createConversation(userId);
    }

    @GetMapping("/conversations")
    public Result<List<AgentConversation>> listConversations(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return aiService.listConversations(userId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<AgentMessage>> listMessages(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return aiService.listMessages(conversationId, userId);
    }

    @PostMapping("/conversations/{conversationId}/clear-context")
    public Result<Void> clearContext(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return aiService.clearConversationContext(conversationId, userId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return aiService.deleteConversation(conversationId, userId);
    }

    @GetMapping("/scheduled-tasks")
    public Result<List<ScheduledTask>> listScheduledTasks(
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        ScheduledTaskService taskService = scheduledTaskServiceProvider.getIfAvailable();
        return taskService == null
                ? Result.fail(null, "定时任务功能未启用；请使用 memory profile 启动服务")
                : Result.ok(taskService.list(userId));
    }

    @GetMapping("/scheduled-tasks/{taskId}/executions")
    public Result<List<ScheduledTaskExecution>> listScheduledTaskExecutions(
            @PathVariable String taskId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        ScheduledTaskService taskService = scheduledTaskServiceProvider.getIfAvailable();
        if (taskService == null) {
            return Result.fail(null, "定时任务功能未启用；请使用 memory profile 启动服务");
        }
        return Result.ok(taskService.history(taskId, userId));
    }

    @DeleteMapping("/scheduled-tasks/{taskId}")
    public Result<Void> deleteScheduledTask(
            @PathVariable String taskId,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        ScheduledTaskService taskService = scheduledTaskServiceProvider.getIfAvailable();
        if (taskService == null) {
            return Result.fail(null, "定时任务功能未启用；请使用 memory profile 启动服务");
        }
        return taskService.delete(taskId, userId)
                ? Result.ok(null)
                : Result.fail(null, "定时任务不存在或无权限访问");
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }
}
