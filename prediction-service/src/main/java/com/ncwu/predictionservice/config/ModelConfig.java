package com.ncwu.predictionservice.config;


import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.agent.WaterStreamingAgent;
import com.ncwu.predictionservice.functionCalling.IotDeviceTools;
import com.ncwu.predictionservice.functionCalling.OtherTools;
import com.ncwu.predictionservice.functionCalling.RepairTools;
import com.ncwu.predictionservice.functionCalling.WaterQueryTools;
import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.ZhipuAiStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/3/5
 */
@Configuration
@RequiredArgsConstructor
public class ModelConfig {
    String key = System.getenv("API_KEY");

    private final WaterQueryTools waterQueryTools;
    private final IotDeviceTools iotDeviceTools;
    private final RepairTools repairTools;
    private final OtherTools otherTools;
    private final AgentTraceContext agentTraceContext;

    @Bean
    public ChatModel initModel() {
        return ZhipuAiChatModel
                .builder()
                .apiKey(key)
                .model("glm-4-plus")
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return ZhipuAiStreamingChatModel.builder()
                .apiKey(key)
                .model("glm-4-plus")
                .toolStream(true)
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public WaterAgent waterAgent(ChatModel chatModel, ObjectProvider<ContentRetriever> contentRetrieverProvider) {
        var builder = AiServices.builder(WaterAgent.class)
                .chatModel(chatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools)
                .afterToolExecution(agentTraceContext::recordToolExecution);
        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            builder.contentRetriever(contentRetriever);
        }
        return builder.build();
    }

    @Bean
    public WaterStreamingAgent waterStreamingAgent(StreamingChatModel streamingChatModel,
                                                    ObjectProvider<ContentRetriever> contentRetrieverProvider) {
        var builder = AiServices.builder(WaterStreamingAgent.class)
                .streamingChatModel(streamingChatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools);
        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            builder.contentRetriever(contentRetriever);
        }
        return builder.build();
    }
}
