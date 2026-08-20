package com.ncwu.predictionservice.config;

import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.agent.WaterInsightAiService;
import com.ncwu.predictionservice.agent.WaterStreamingAgent;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import com.ncwu.predictionservice.functionCalling.IotDeviceTools;
import com.ncwu.predictionservice.functionCalling.OtherTools;
import com.ncwu.predictionservice.functionCalling.RepairTools;
import com.ncwu.predictionservice.functionCalling.WaterQueryTools;
import com.ncwu.predictionservice.task.ScheduledTaskTools;
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
                                 ObjectProvider<ConversationRepository> conversationRepositoryProvider,
                                 ObjectProvider<ScheduledTaskTools> scheduledTaskToolsProvider) {
        var builder = AiServices.builder(WaterAgent.class)
                .chatModel(chatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools);
        scheduledTaskToolsProvider.ifAvailable(builder::tools);
        builder.afterToolExecution(agentTraceContext::recordToolExecution);
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
            // 即使关闭持久化会话，@MemoryId 仍要求提供 Provider。这里保留受限的进程内上下文，
            // 它不持久化，仅服务于独立 local profile 下的一次请求或流式调用。
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.withMaxMessages(20));
        }
        if (conversationRepository != null) {
            // 数据库摘要与会话一一对应，因此按 memoryId 动态构造系统提示词。
            builder.systemMessageProvider(conversationId -> WaterAgent.SYSTEM_MESSAGE
                    + conversationRepository.longTermContext(conversationId.toString()));
        }
        return builder.build();
    }

    /**
     * 面向预测、评语等机器消费结果的模型代理。
     * 与 WaterAgent 分离，避免这些独立任务携带对话记忆、RAG 和工具调用。
     */
    @Bean
    public WaterInsightAiService waterInsightAiService(ChatModel chatModel) {
        return AiServices.builder(WaterInsightAiService.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    public WaterStreamingAgent waterStreamingAgent(StreamingChatModel streamingChatModel,
                                                    ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                                    ObjectProvider<ChatMemoryStore> chatMemoryStoreProvider,
                                                    ObjectProvider<ConversationRepository> conversationRepositoryProvider,
                                                    ObjectProvider<ScheduledTaskTools> scheduledTaskToolsProvider) {
        var builder = AiServices.builder(WaterStreamingAgent.class)
                .streamingChatModel(streamingChatModel)
                .tools(waterQueryTools, iotDeviceTools, repairTools, otherTools);
        scheduledTaskToolsProvider.ifAvailable(builder::tools);
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
            // memory profile 未启用时，仍需为流式 Agent 的 @MemoryId 提供有效 Provider。
            builder.chatMemoryProvider(conversationId -> MessageWindowChatMemory.withMaxMessages(20));
        }
        if (conversationRepository != null) {
            builder.systemMessageProvider(conversationId -> WaterAgent.SYSTEM_MESSAGE
                    + conversationRepository.longTermContext(conversationId.toString()));
        }
        return builder.build();
    }
}
