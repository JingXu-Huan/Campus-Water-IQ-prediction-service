package com.ncwu.predictionservice.trace;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects trace events emitted by one streaming Agent invocation.
 * Streaming callbacks can run on model-client threads while SSE serialization runs on another thread,
 * hence the copy-on-write lists rather than the ThreadLocal used by synchronous calls.
 */
public final class AgentStreamTraceCollector {

    private static final int MAX_SUMMARY_LENGTH = 180;
    private static final int MAX_EXCERPT_LENGTH = 220;
    private final List<AgentTrace.ToolCall> tools = new CopyOnWriteArrayList<>();
    private final List<AgentTrace.RagReference> ragReferences = new CopyOnWriteArrayList<>();

    public void recordToolExecution(ToolExecution execution) {
        tools.add(new AgentTrace.ToolCall(
                execution.request().name(),
                abbreviate(execution.result(), MAX_SUMMARY_LENGTH),
                execution.duration().toMillis(),
                execution.hasFailed() ? "FAILED" : "COMPLETED"));
    }

    public void recordRetrievedContent(List<Content> contents) {
        for (Content content : contents) {
            String source = content.textSegment().metadata().getString("source");
            Object score = content.metadata().get(ContentMetadata.SCORE);
            ragReferences.add(new AgentTrace.RagReference(
                    source == null || source.isBlank() ? "知识库片段" : source,
                    score instanceof Number number ? number.doubleValue() : null,
                    abbreviate(content.textSegment().text(), MAX_EXCERPT_LENGTH)));
        }
    }

    public AgentTrace snapshot() {
        return new AgentTrace(List.copyOf(tools), List.copyOf(ragReferences));
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "无返回内容";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "…";
    }
}
