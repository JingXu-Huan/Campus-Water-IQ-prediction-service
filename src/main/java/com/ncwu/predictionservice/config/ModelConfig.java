package com.ncwu.predictionservice.config;


import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import com.ncwu.predictionservice.functionCalling.IotDeviceTools;
import com.ncwu.predictionservice.functionCalling.OtherTools;
import com.ncwu.predictionservice.functionCalling.RepairTools;
import com.ncwu.predictionservice.functionCalling.WaterQueryTools;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
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
    public WaterAgent waterAgent(ChatModel chatModel,
                                 ChatMemoryStore chatMemoryStore,
                                 ConversationRepository conversationRepository) {
        return AiServices.builder(WaterAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(conversationId -> MessageWindowChatMemory.builder()
                        .id(conversationId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .systemMessageProvider(conversationId -> WaterAgent.BASE_SYSTEM_PROMPT
                        + conversationRepository.longTermContext(conversationId.toString()))
                .tools(waterQueryTools,iotDeviceTools,repairTools,otherTools)   // ← Tools在这里注册
                .build();
    }
}
