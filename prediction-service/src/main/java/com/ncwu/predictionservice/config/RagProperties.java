package com.ncwu.predictionservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private boolean enabled = true;
    private String knowledgeLocation = "classpath*:knowledge/*.md";
    private int maxResults = 4;
    private double minScore = 0.65D;
    private Embedding embedding = new Embedding();
    private PgVector pgvector = new PgVector();

    @Getter
    @Setter
    public static class Embedding {
        private String apiKey;
        private String model = "embedding-3";
        private int dimensions = 1024;
    }

    @Getter
    @Setter
    public static class PgVector {
        private String host = "localhost";
        private int port = 5432;
        private String database = "campus_water";
        private String user = "campus_water";
        private String password = "campus_water";
        private String table = "rag_embeddings";
    }
}
