package com.ncwu.predictionservice.controller;


import com.ncwu.common.apis.IoTDataServiceApi;
import com.ncwu.common.apis.iot_service.IotDataService;
import com.ncwu.common.domain.bo.ToAIBO;
import com.ncwu.common.domain.vo.Result;
import com.ncwu.predictionservice.service.AiService;
import com.ncwu.predictionservice.domain.vo.UsageVO;
import com.ncwu.predictionservice.agent.WaterStreamingAgent;
import com.ncwu.predictionservice.trace.AgentChatResponse;
import com.ncwu.predictionservice.trace.AgentStreamTraceCollector;
import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.io.IOException;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/3/4
 */
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

    /**
     * 预测某校区明天的用水量（自动获取近七天数据）
     */
    @PostMapping("/predictTomorrowWaterUsage")
    public Result<UsageVO> predictTomorrowWaterUsage(@Min(1) @Max(3) @RequestParam int campus) {
        try {
            // 通过 Dubbo 调用 IoT-service 获取近七天的用水数据
            Result<ToAIBO> response = ioTDataServiceApi.getRecentWeekUsage();

            if (response == null || response.getData() == null) {
                return Result.fail(null, "获取用水数据失败");
            }

            ToAIBO toAIBO = response.getData();
            List<Double> usageData;

            // 根据校区获取对应的用水数据
            switch (campus) {
                case 1 -> usageData = toAIBO.getHY();
                case 2 -> usageData = toAIBO.getLH();
                case 3 -> usageData = toAIBO.getJH();
                default -> usageData = List.of();
            }

            if (usageData == null || usageData.isEmpty()) {
                return Result.fail(null, "暂无用水数据");
            }

            return aiService.predictTomorrowWaterUsage(usageData, campus);
        } catch (Exception e) {
            log.error("调用 IoT-service 失败: {}", e.getMessage(), e);
            return Result.fail(null, "获取用水数据失败: " + e.getMessage());
        }
    }

    /**
     * 评价水质，给出水质建议
     *
     * @param score 分数
     * @param ch    含氯量
     * @param th    浊度
     * @param ph    酸碱度
     */
    @PostMapping("/suggestionOfWater")
    public Result<String> suggestionOfWater(double score, double ph, double ch, double th) {
        return aiService.suggestionOfWater((int) score, ph, ch, th);
    }

    /**
     * 给出一条节水建议
     */
    @PostMapping("/suggestions")
    public Result<String> giveSuggestions() {
        return aiService.suggestionOfWaterUsage();
    }

    /**
     * 给出一条设备水质合格率的评价
     */
    @GetMapping("/suggestionOfDevice")
    public Result<String> suggestionOfDevice() {
        Result<Double> qualityRate = iotDataService.getQualityRate();
        Double data = qualityRate.getData();
        return aiService.suggestionOfDevice(data);
    }

    /**
     * 用户与ai进行交互，支持工具调用。
     * @param input 用户输入
     */
    @PostMapping("/chatWithAgent")
    public Result<AgentChatResponse> chat(@RequestParam String input) {
        //用户输入内容例如：我想知道某校区某类型楼宇的某用水单元的某项数据。
        //Agent 要知道调用哪些接口，返回什么数据
        try (AgentTraceContext.ActiveTrace activeTrace = agentTraceContext.begin()) {
            Result<String> result = aiService.chatWithAgent(input);
            if (result.getData() == null) {
                return Result.fail(null, result.getMessage());
            }
            return Result.ok(new AgentChatResponse(result.getData(), activeTrace.snapshot()));
        }
    }

    /**
     * Streams answer tokens as SSE events. Event names: delta, trace, done and error.
     */
    @PostMapping(value = "/chatWithAgent/stream", produces = "text/event-stream")
    public SseEmitter streamChat(@RequestParam String input) {
        SseEmitter emitter = new SseEmitter(0L);
        AgentStreamTraceCollector traceCollector = new AgentStreamTraceCollector();
        try {
            TokenStream tokenStream = waterStreamingAgent.chat(input);
            tokenStream
                    .onPartialResponse(token -> send(emitter, "delta", token))
                    .onRetrieved(contents -> {
                        traceCollector.recordRetrievedContent(contents);
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

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }


}
