# Prediction Service

This is a standalone Maven extraction of the prediction-service. The Dubbo
contracts it consumes are included under `src/main/java/com/ncwu/common`, so
the original multi-module project is not required to build it.

## Prerequisites

- JDK 21
- Redis on `localhost:6379` and PGVector on `localhost:5432`. Start both with
  `docker compose up -d` when Docker is available.
- A Zhipu AI key in the `API_KEY` environment variable. It is used by both the
  GLM chat model and the `embedding-3` RAG embedding model. You may set
  `ZHIPU_EMBEDDING_API_KEY` separately if the embedding key must differ.

The default `local` profile uses deterministic mock implementations for the
IoT, device, and repair-service APIs. It therefore does not need Nacos, Dubbo,
or any other Campus-Water-IQ service to run.

## Run

```powershell
$env:API_KEY = "your-zhipu-ai-key"
.\mvnw.cmd spring-boot:run
```

To build a runnable JAR:

```powershell
.\mvnw.cmd clean package
java -jar .\target\prediction-service-0.0.1-SNAPSHOT.jar
```

## RAG knowledge base

The local profile enables RAG by default. On its first start, the service reads
Markdown files from `src/main/resources/knowledge`, splits them into chunks,
creates 1024-dimensional embeddings with Zhipu `embedding-3`, and persists
them in the `rag_embeddings` table of PGVector. The `WaterAgent` retrieves the
four most relevant chunks for each question before invoking the chat model.

The service records a checksum of the bundled knowledge files. It reindexes the
dedicated RAG table only when those files change; no campus business table is
touched. Useful environment variables are:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PGVECTOR_HOST` / `PGVECTOR_PORT` | `localhost` / `5432` | PGVector address |
| `PGVECTOR_DATABASE` / `PGVECTOR_USER` / `PGVECTOR_PASSWORD` | `campus_water` | Local database credentials |
| `PGVECTOR_TABLE` | `rag_embeddings` | Dedicated vector table name |
| `ZHIPU_EMBEDDING_MODEL` | `embedding-3` | Zhipu embedding model |
| `ZHIPU_EMBEDDING_DIMENSIONS` | `1024` | Embedding and vector dimension; changing it requires a new table or a reset |
| `RAG_MAX_RESULTS` / `RAG_MIN_SCORE` | `4` / `0.65` | Retrieval count and similarity threshold |
| `RAG_ENABLED` | `true` | Set to `false` to start without PGVector or RAG |

## Agent trace response

`POST /ai/chatWithAgent?input=...` now returns the answer together with an
execution trace. `trace.tools` is populated only by actual LangChain4j tool
executions; `trace.ragReferences` lists the Markdown source file, retrieval
score, and a short excerpt for each knowledge-base chunk used as context.

```json
{
  "data": {
    "answer": "...",
    "trace": {
      "tools": [{"name": "getSchoolUsage", "resultSummary": "...", "durationMs": 742, "status": "COMPLETED"}],
      "ragReferences": [{"source": "08-water-data-quality-and-anomaly.md", "score": 0.812, "excerpt": "..."}]
    }
  }
}
```

## Streaming Agent response

The frontend uses `POST /ai/chatWithAgent/stream?input=...` with
`Accept: text/event-stream`. It receives these SSE events:

| Event | Data | When it is sent |
| --- | --- | --- |
| `delta` | A text fragment | While GLM generates the answer |
| `trace` | The current `AgentTrace` JSON | After RAG retrieval or a tool execution, and once more at completion |
| `done` | Empty | The Agent invocation completed successfully |
| `error` | Error message | The model or streaming pipeline failed |

The existing non-streaming `POST /ai/chatWithAgent` endpoint remains available
for clients that expect a single JSON response.
