package com.ncwu.predictionservice.config;

import com.ncwu.predictionservice.trace.AgentTraceContext;
import dev.langchain4j.community.model.zhipu.ZhipuAiEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverListener;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
// RAG 为可选能力，缺少 PGVector 或嵌入模型密钥时，提取后的服务仍可独立运行。
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private final RagProperties ragProperties;

    /**
     * 索引任务可能连续调用嵌入模型，使用单线程可避免同一知识库被并发重复写入。
     * 应用启动只负责提交任务，不等待索引完成。
     */
    @Bean("ragIndexingExecutor")
    public Executor ragIndexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("rag-indexing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    public EmbeddingModel zhipuEmbeddingModel() {
        RagProperties.Embedding embedding = ragProperties.getEmbedding();
        if (!StringUtils.hasText(embedding.getApiKey())) {
            throw new IllegalStateException("RAG 已启用，但未配置 ZHIPU_EMBEDDING_API_KEY 或 API_KEY");
        }
        return ZhipuAiEmbeddingModel.builder()
                .apiKey(embedding.getApiKey())
                .model(embedding.getModel())
                .dimensions(embedding.getDimensions())
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> pgVectorEmbeddingStore(EmbeddingModel zhipuEmbeddingModel) {
        RagProperties.PgVector pgVector = ragProperties.getPgvector();
        return PgVectorEmbeddingStore.builder()
                .host(pgVector.getHost())
                .port(pgVector.getPort())
                .database(pgVector.getDatabase())
                .user(pgVector.getUser())
                .password(pgVector.getPassword())
                .table(pgVector.getTable())
                .dimension(zhipuEmbeddingModel.dimension())
                .useIndex(true)
                .indexListSize(100)
                .createTable(true)
                .build();
    }

    @Bean
    public ContentRetriever ragContentRetriever(EmbeddingModel zhipuEmbeddingModel,
                                                EmbeddingStore<TextSegment> pgVectorEmbeddingStore,
                                                AgentTraceContext agentTraceContext) {
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(zhipuEmbeddingModel)
                .embeddingStore(pgVectorEmbeddingStore)
                .maxResults(ragProperties.getMaxResults())
                .minScore(ragProperties.getMinScore())
                .build();
        // 非流式 Agent 没有检索事件回调；在此挂载监听器后，其请求级 trace 才能和流式接口一样
        // 返回引用的知识库资料。
        return retriever.addListener(new ContentRetrieverListener() {
            @Override
            public void onResponse(ContentRetrieverResponseContext response) {
                agentTraceContext.recordRetrievedContent(response);
            }
        });
    }
}
