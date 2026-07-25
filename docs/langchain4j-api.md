# LangChain4j 1.18 API reference

This service uses LangChain4j `1.18.0` and the Zhipu AI Community adapter
`1.18.0-beta28`. The Community adapter release is paired with the stable
LangChain4j 1.18 core release.

## APIs used by this service

| API | Usage in this service | Official documentation |
| --- | --- | --- |
| `ChatModel` | Direct single-turn generation through `chat(String)` | [Chat and Language Models](https://docs.langchain4j.dev/tutorials/chat-and-language-models/) |
| `ChatRequest` / `ChatResponse` | Advanced per-request messages, model parameters and tool specifications | [Chat and Language Models](https://docs.langchain4j.dev/tutorials/chat-and-language-models/) |
| `ZhipuAiChatModel` | Creates the GLM model client with API key, model and timeouts | [Zhipu AI integration](https://docs.langchain4j.dev/integrations/language-models/zhipu-ai/) |
| `ZhipuAiEmbeddingModel` | Creates 1024-dimensional `embedding-3` vectors for campus-water knowledge | [Zhipu AI integration](https://docs.langchain4j.dev/integrations/language-models/zhipu-ai/) |
| `AiServices` | Builds the `WaterAgent` interface proxy | [AI Services](https://docs.langchain4j.dev/tutorials/ai-services/) |
| `@SystemMessage` / `@UserMessage` | Declares the agent system prompt and user prompt template | [AI Services](https://docs.langchain4j.dev/tutorials/ai-services/) |
| `@Tool` | Exposes campus-water query and device operations for model tool calling | [Tools (Function Calling)](https://docs.langchain4j.dev/tutorials/tools/) |
| `EmbeddingStoreContentRetriever` | Retrieves relevant PGVector chunks and supplies them to the agent | [RAG](https://docs.langchain4j.dev/tutorials/rag/) |
| `PgVectorEmbeddingStore` | Stores and queries the persistent campus-water embeddings | [PGVector](https://docs.langchain4j.dev/integrations/embedding-stores/pgvector/) |
| `ChatRequestParameters` | Optional per-call configuration such as temperature and maximum output tokens | [Model Parameters](https://docs.langchain4j.dev/tutorials/model-parameters/) |

## Migration in this project

The former `ChatLanguageModel` API has been replaced with `ChatModel`.
`AiServices.builder(...).chatLanguageModel(...)` is now
`AiServices.builder(...).chatModel(...)`. For the existing `String`-to-`String`
calls, `ChatModel.chat(String)` preserves the behaviour. Use `ChatRequest` when
you need structured messages, request-scoped settings, or explicit tool choice.

## Dependency management

Both dependencies intentionally omit an individual version. Maven imports
`langchain4j-bom` and `langchain4j-community-bom` to keep the core and the
Zhipu Community adapter compatible.
