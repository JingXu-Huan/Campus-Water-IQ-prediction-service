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
import org.springframework.util.StringUtils;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RagProperties.class)
// RAG is optional so the extracted service can still run without PGVector or an embedding key.
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private final RagProperties ragProperties;

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
        // The non-streaming Agent has no callback for retrieval events; attach here so its
        // request-scoped trace can expose the same references as the streaming endpoint.
        return retriever.addListener(new ContentRetrieverListener() {
            @Override
            public void onResponse(ContentRetrieverResponseContext response) {
                agentTraceContext.recordRetrievedContent(response);
            }
        });
    }
}
