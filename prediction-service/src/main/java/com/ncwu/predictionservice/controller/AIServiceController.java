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
import com.ncwu.predictionservice.trace.AgentStreamTraceCollector;
import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

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
                    result.getData().answer(), activeTrace.snapshot()));
        }
    }

    @PostMapping(value = "/chatWithAgent/stream", produces = "text/event-stream")
    public SseEmitter streamChat(@RequestParam String input,
                                 @RequestParam(required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(0L);
        AgentStreamTraceCollector traceCollector = new AgentStreamTraceCollector();
        // A stream without a persistent conversation must never share a memory window with a
        // different browser request. A supplied conversation id is used only in the memory profile.
        String memoryId = conversationId == null || conversationId.isBlank() ? "stream-" + UUID.randomUUID() : conversationId;
        try {
            TokenStream tokenStream = waterStreamingAgent.chat(memoryId, input);
            tokenStream
                    .onPartialResponse(token -> send(emitter, "delta", token))
                    .onRetrieved(contents -> {
                        traceCollector.recordRetrievedContent(contents);
                        // Emit incremental snapshots so the UI can show provenance before the
                        // model finishes composing its answer.
                        send(emitter, "trace", traceCollector.snapshot());
                    })
                    .onToolExecuted(execution -> {
                        traceCollector.recordToolExecution(execution);
                        send(emitter, "trace", traceCollector.snapshot());
                    })
                    .onCompleteResponse(ignored -> {
                        send(emitter, "trace", traceCollector.snapshot());
                        send(emitter, "done", "");
                        emitter.complete();
                    })
                    .onError(error -> {
                        log.error("流式 Agent 调用失败", error);
                        send(emitter, "error", error.getMessage() == null ? "调用模型失败，请稍后重试" : error.getMessage());
                        emitter.completeWithError(error);
                    })
                    .start();
        } catch (Exception error) {
            log.error("启动流式 Agent 调用失败", error);
            send(emitter, "error", error.getMessage() == null ? "调用模型失败，请稍后重试" : error.getMessage());
            emitter.completeWithError(error);
        }
        return emitter;
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

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }
}
