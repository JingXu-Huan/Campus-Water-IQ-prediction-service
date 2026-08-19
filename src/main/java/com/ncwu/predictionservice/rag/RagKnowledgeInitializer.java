package com.ncwu.predictionservice.rag;

import com.ncwu.predictionservice.config.RagProperties;
import com.ncwu.predictionservice.rag.chunking.DocumentChunkingProcessor;
import com.ncwu.predictionservice.rag.chunking.MarkdownDocumentChunkingProcessor;
import com.ncwu.predictionservice.rag.chunking.RecursiveDocumentChunkingProcessor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 将 classpath 中的知识库文件增量写入 PGVector。
 *
 * <p>状态表按文件保存内容指纹，而非保存整个知识库的总指纹。因此新增、修改、删除或改名时，
 * 只会删除并重新向量化受影响的文件；未变化文件不会再次调用嵌入模型。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagKnowledgeInitializer implements ApplicationRunner {

    private static final String LEGACY_STATE_TABLE = "rag_ingestion_state";
    private static final String DOCUMENT_STATE_TABLE = "rag_document_ingestion_state";
    private static final String KNOWLEDGE_SET = "classpath-knowledge";
    private static final String DOCUMENT_ID_METADATA_KEY = "rag_document_id";

    private final RagProperties ragProperties;
    private final EmbeddingModel zhipuEmbeddingModel;
    private final EmbeddingStore<TextSegment> pgVectorEmbeddingStore;
    private final Executor ragIndexingExecutor;
    // Markdown 保留标题层级；纯文本使用通用递归切分策略。
    private final List<DocumentChunkingProcessor> documentChunkingProcessors = List.of(
            new MarkdownDocumentChunkingProcessor(),
            new RecursiveDocumentChunkingProcessor());

    public RagKnowledgeInitializer(RagProperties ragProperties, EmbeddingModel zhipuEmbeddingModel,
                                   EmbeddingStore<TextSegment> pgVectorEmbeddingStore,
                                   @Qualifier("ragIndexingExecutor") Executor ragIndexingExecutor) {
        this.ragProperties = ragProperties;
        this.zhipuEmbeddingModel = zhipuEmbeddingModel;
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.ragIndexingExecutor = ragIndexingExecutor;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<KnowledgeDocument> documents = loadKnowledgeDocuments();
        CompletableFuture.runAsync(() -> synchronizeKnowledge(documents), ragIndexingExecutor)
                .whenComplete((unused, exception) -> {
                    if (exception == null) {
                        log.info("RAG 异步索引任务完成（{} 个文档）", documents.size());
                    } else {
                        log.error("RAG 异步索引任务失败", exception);
                    }
                });
        log.info("RAG 异步索引任务已提交（{} 个文档）", documents.size());
    }

    private void synchronizeKnowledge(List<KnowledgeDocument> documents) {
        try (Connection connection = openConnection()) {
            createStateTables(connection);
            migrateLegacyIndexIfNecessary(connection);

            Map<String, IndexedDocument> indexedDocuments = readIndexedDocuments(connection);
            Set<String> currentDocumentIds = documents.stream()
                    .map(KnowledgeDocument::id)
                    .collect(Collectors.toSet());

            int indexedCount = 0;
            for (KnowledgeDocument document : documents) {
                IndexedDocument indexedDocument = indexedDocuments.get(document.id());
                if (indexedDocument != null && indexedDocument.checksum().equals(document.checksum())) {
                    continue;
                }

                if (indexedDocument != null) {
                    // 先按文件标识清理旧分片，避免修改后的内容与旧内容同时参与召回。
                    removeDocumentEmbeddings(document.id());
                }
                ingest(document);
                saveIndexedDocument(connection, document);
                indexedCount++;
            }

            int removedCount = 0;
            for (IndexedDocument indexedDocument : indexedDocuments.values()) {
                if (!currentDocumentIds.contains(indexedDocument.id())) {
                    // 文件被删除或改名时，只移除这一个旧文件的向量分片。
                    removeDocumentEmbeddings(indexedDocument.id());
                    deleteIndexedDocument(connection, indexedDocument.id());
                    removedCount++;
                }
            }

            if (indexedCount == 0 && removedCount == 0) {
                log.info("RAG 知识库已是最新版本，跳过索引（{} 个文档）", documents.size());
            } else {
                log.info("RAG 知识库增量索引完成：新增或更新 {} 个文档，移除 {} 个文档", indexedCount, removedCount);
            }
        } catch (SQLException exception) {
            throw new CompletionException("RAG 知识库索引失败", exception);
        }
    }

    private List<KnowledgeDocument> loadKnowledgeDocuments() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(ragProperties.getKnowledgeLocation());
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String source = resource.getFilename();
            if (source == null) {
                throw new IllegalStateException("无法识别 RAG 知识文件名：" + resource.getDescription());
            }
            Document loadedDocument = loadDocument(resource, source);
            String content = loadedDocument.text();
            String checksum = checksum(content);
            // 文件名同时作为状态表主键和向量元数据，便于按单文件清理与在 trace 中展示来源。
            Metadata metadata = Metadata.from(Map.of(
                    "source", source,
                    DOCUMENT_ID_METADATA_KEY, source,
                    "rag_content_checksum", checksum));
            documents.add(new KnowledgeDocument(source, checksum, Document.from(content, metadata)));
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("RAG 知识库为空：" + ragProperties.getKnowledgeLocation());
        }
        documents.sort(Comparator.comparing(KnowledgeDocument::id));
        return documents;
    }

    /**
     * 由 LangChain4j 的加载器负责将文件转为 {@link Document}。
     * 开发环境中的 classpath 资源通常是本地文件，优先使用 FileSystemDocumentLoader；
     * 打包成可执行 JAR 后则改用 ClassPathDocumentLoader，避免尝试将 JAR 内条目当成普通文件。
     */
    private Document loadDocument(Resource resource, String source) throws IOException {
        if (resource.isFile()) {
            return FileSystemDocumentLoader.loadDocument(resource.getFile().toPath());
        }
        return ClassPathDocumentLoader.loadDocument(classpathPath(resource, source));
    }

    private String classpathPath(Resource resource, String source) throws IOException {
        URL url = resource.getURL();
        String externalForm = url.toExternalForm();
        int jarEntryPrefix = externalForm.indexOf("!/");
        if (jarEntryPrefix >= 0) {
            return externalForm.substring(jarEntryPrefix + 2);
        }
        return "knowledge/" + source;
    }

    private void ingest(KnowledgeDocument document) {
        DocumentChunkingProcessor processor = findChunkingProcessor(document.id());
        List<Document> preparedDocuments = processor.prepare(document.document());
        // 所有预处理结果最终都经过递归切分，避免单个章节或 Word 段落过长。
        DocumentSplitter splitter = DocumentSplitters.recursive(800, 120);
        EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(zhipuEmbeddingModel)
                .embeddingStore(pgVectorEmbeddingStore)
                .build()
                .ingest(preparedDocuments);
        log.info("已索引 RAG 文档：{}（{}，{} 个预处理单元）",
                document.id(), processor.strategyName(), preparedDocuments.size());
    }

    private DocumentChunkingProcessor findChunkingProcessor(String fileName) {
        return documentChunkingProcessors.stream()
                .filter(processor -> processor.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RAG 不支持的知识文件类型：" + fileName));
    }

    private void removeDocumentEmbeddings(String documentId) {
        pgVectorEmbeddingStore.removeAll(metadataKey(DOCUMENT_ID_METADATA_KEY).isEqualTo(documentId));
    }

    /**
     * 旧版本只保存了知识库总校验和，历史向量没有单文件元数据，无法安全地逐文件删除。
     * 升级后的首次启动执行一次全量迁移；之后永远采用按文件的增量索引。
     */
    private void migrateLegacyIndexIfNecessary(Connection connection) throws SQLException {
        if (!hasLegacyState(connection) || hasDocumentState(connection)) {
            return;
        }
        log.info("检测到旧版 RAG 全量索引，正在执行一次性迁移");
        pgVectorEmbeddingStore.removeAll();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + LEGACY_STATE_TABLE + " WHERE knowledge_set = ?")) {
            statement.setString(1, KNOWLEDGE_SET);
            statement.executeUpdate();
        }
    }

    private String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算 RAG 文档内容指纹", exception);
        }
    }

    private Connection openConnection() throws SQLException {
        RagProperties.PgVector pgVector = ragProperties.getPgvector();
        String jdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(
                pgVector.getHost(), pgVector.getPort(), pgVector.getDatabase());
        return DriverManager.getConnection(jdbcUrl, pgVector.getUser(), pgVector.getPassword());
    }

    private void createStateTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rag_ingestion_state (
                        knowledge_set VARCHAR(128) PRIMARY KEY,
                        checksum VARCHAR(64) NOT NULL,
                        indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rag_document_ingestion_state (
                        knowledge_set VARCHAR(128) NOT NULL,
                        document_id VARCHAR(512) NOT NULL,
                        checksum VARCHAR(64) NOT NULL,
                        indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (knowledge_set, document_id)
                    )
                    """);
        }
    }

    private Map<String, IndexedDocument> readIndexedDocuments(Connection connection) throws SQLException {
        Map<String, IndexedDocument> documents = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT document_id, checksum
                FROM rag_document_ingestion_state
                WHERE knowledge_set = ?
                """)) {
            statement.setString(1, KNOWLEDGE_SET);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String id = resultSet.getString("document_id");
                    documents.put(id, new IndexedDocument(id, resultSet.getString("checksum")));
                }
            }
        }
        return documents;
    }

    private boolean hasLegacyState(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + LEGACY_STATE_TABLE + " WHERE knowledge_set = ?")) {
            statement.setString(1, KNOWLEDGE_SET);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean hasDocumentState(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + DOCUMENT_STATE_TABLE + " WHERE knowledge_set = ? LIMIT 1")) {
            statement.setString(1, KNOWLEDGE_SET);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void saveIndexedDocument(Connection connection, KnowledgeDocument document) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rag_document_ingestion_state (knowledge_set, document_id, checksum)
                VALUES (?, ?, ?)
                ON CONFLICT (knowledge_set, document_id)
                DO UPDATE SET checksum = EXCLUDED.checksum, indexed_at = CURRENT_TIMESTAMP
                """)) {
            statement.setString(1, KNOWLEDGE_SET);
            statement.setString(2, document.id());
            statement.setString(3, document.checksum());
            statement.executeUpdate();
        }
    }

    private void deleteIndexedDocument(Connection connection, String documentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM rag_document_ingestion_state
                WHERE knowledge_set = ? AND document_id = ?
                """)) {
            statement.setString(1, KNOWLEDGE_SET);
            statement.setString(2, documentId);
            statement.executeUpdate();
        }
    }

    private record KnowledgeDocument(String id, String checksum, Document document) {
    }

    private record IndexedDocument(String id, String checksum) {
    }
}
