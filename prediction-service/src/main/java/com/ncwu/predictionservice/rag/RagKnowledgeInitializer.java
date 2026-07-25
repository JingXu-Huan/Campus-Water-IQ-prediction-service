package com.ncwu.predictionservice.rag;

import com.ncwu.predictionservice.config.RagProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagKnowledgeInitializer implements ApplicationRunner {

    private static final String STATE_TABLE = "rag_ingestion_state";
    private static final String KNOWLEDGE_SET = "classpath-knowledge";

    private final RagProperties ragProperties;
    private final EmbeddingModel zhipuEmbeddingModel;
    private final EmbeddingStore<TextSegment> pgVectorEmbeddingStore;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Document> documents = loadKnowledgeDocuments();
        String checksum = checksum(documents);
        try (Connection connection = openConnection()) {
            createStateTable(connection);
            if (checksum.equals(readIndexedChecksum(connection))) {
                log.info("RAG 知识库已是最新版本，跳过索引（{} 个文档）", documents.size());
                return;
            }

            log.info("开始构建 RAG 知识库索引（{} 个文档）", documents.size());
            pgVectorEmbeddingStore.removeAll();
            DocumentSplitter splitter = DocumentSplitters.recursive(300, 50);
            EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(zhipuEmbeddingModel)
                    .embeddingStore(pgVectorEmbeddingStore)
                    .build()
                    .ingest(documents);
            saveIndexedChecksum(connection, checksum);
            log.info("RAG 知识库索引完成");
        }
    }

    private List<Document> loadKnowledgeDocuments() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(ragProperties.getKnowledgeLocation());
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            documents.add(Document.from(content, Metadata.from("source", resource.getFilename())));
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("RAG 知识库为空：" + ragProperties.getKnowledgeLocation());
        }
        return documents;
    }

    private String checksum(List<Document> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            documents.stream()
                    .map(document -> document.text())
                    .sorted(Comparator.naturalOrder())
                    .forEach(text -> digest.update(text.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算 RAG 知识库校验值", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        RagProperties.PgVector pgVector = ragProperties.getPgvector();
        String jdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                pgVector.getHost(), pgVector.getPort(), pgVector.getDatabase());
        return DriverManager.getConnection(jdbcUrl, pgVector.getUser(), pgVector.getPassword());
    }

    private void createStateTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rag_ingestion_state (
                        knowledge_set VARCHAR(128) PRIMARY KEY,
                        checksum VARCHAR(64) NOT NULL,
                        indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private String readIndexedChecksum(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum FROM " + STATE_TABLE + " WHERE knowledge_set = ?")) {
            statement.setString(1, KNOWLEDGE_SET);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private void saveIndexedChecksum(Connection connection, String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rag_ingestion_state (knowledge_set, checksum)
                VALUES (?, ?)
                ON CONFLICT (knowledge_set)
                DO UPDATE SET checksum = EXCLUDED.checksum, indexed_at = CURRENT_TIMESTAMP
                """)) {
            statement.setString(1, KNOWLEDGE_SET);
            statement.setString(2, checksum);
            statement.executeUpdate();
        }
    }
}
