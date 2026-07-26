package com.ncwu.predictionservice.config;

import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.agent.WaterStreamingAgent;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import com.ncwu.predictionservice.functionCalling.IotDeviceTools;
import com.ncwu.predictionservice.functionCalling.OtherTools;
import com.ncwu.predictionservice.functionCalling.RepairTools;
import com.ncwu.predictionservice.functionCalling.WaterQueryTools;
import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.ZhipuAiStreamingChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class ModelConfig {

    private final String key = System.getenv("API_KEY");
    private final WaterQueryTools waterQueryTools;
    private final IotDeviceTools iotDeviceTools;
    private final RepairTools repairTools;
    private final OtherTools otherTools;
    private final AgentTraceContext agentTraceContext;

    @Bean
    public ChatModel initModel() {
        return ZhipuAiChatModel.builder()
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
    public WaterAgent waterAgent(ChatModel chatModel,
                                 ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                 ObjectProvider<ChatMemoryStore> chatMemoryStoreProvider,
                                 ObjectProvider<ConversationRepository> conversationRepositoryProvider) {
        var builder = AiServices.builder(WaterAgent.class)
                .chatModel(chatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools)
                .afterToolExecution(agentTraceContext::recordToolExecution);
        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            builder.contentRetriever(contentRetriever);
        }
        ChatMemoryStore chatMemoryStore = chatMemoryStoreProvider.getIfAvailable();
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        if (chatMemoryStore != null) {
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.builder()
                    .id(conversationId)
                    .maxMessages(20)
                    .chatMemoryStore(chatMemoryStore)
                    .build());
        } else {
            // @MemoryId requires a provider even when persistent conversation
            // storage is disabled. Keep a bounded, process-local context here; it is intentionally
            // not durable and only supports one request/stream in the standalone local profile.
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.withMaxMessages(20));
        }
        if (conversationRepository != null) {
            // Build this per memory id because the database summary is conversation-specific.
            builder.systemMessageProvider(conversationId -> WaterAgent.SYSTEM_MESSAGE
                    + conversationRepository.longTermContext(conversationId.toString()));
        }
        return builder.build();
    }

    @Bean
    public WaterStreamingAgent waterStreamingAgent(StreamingChatModel streamingChatModel,
                                                    ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                                    ObjectProvider<ChatMemoryStore> chatMemoryStoreProvider,
                                                    ObjectProvider<ConversationRepository> conversationRepositoryProvider) {
        var builder = AiServices.builder(WaterStreamingAgent.class)
                .streamingChatModel(streamingChatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools);
        ContentRetriever contentRetriever = contentRetrieverProvider.getIfAvailable();
        if (contentRetriever != null) {
            builder.contentRetriever(contentRetriever);
        }
        ChatMemoryStore chatMemoryStore = chatMemoryStoreProvider.getIfAvailable();
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        if (chatMemoryStore != null) {
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.builder()
                    .id(conversationId)
                    .maxMessages(20)
                    .chatMemoryStore(chatMemoryStore)
                    .build());
        } else {
            // Keep the streaming Agent valid with @MemoryId even when the memory profile is off.
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.withMaxMessages(20));
        }
        if (conversationRepository != null) {
            builder.systemMessageProvider(conversationId -> WaterAgent.SYSTEM_MESSAGE
                    + conversationRepository.longTermContext(conversationId.toString()));
        }
        return builder.build();
    }
}
