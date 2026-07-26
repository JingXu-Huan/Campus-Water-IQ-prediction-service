package com.ncwu.predictionservice.trace;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.listener.ContentRetrieverResponseContext;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentTraceContext {

    private static final int MAX_SUMMARY_LENGTH = 180;
    private static final int MAX_EXCERPT_LENGTH = 220;
    // 普通 Agent 调用的工具回调位于请求线程；ThreadLocal 可避免并发 HTTP 请求混入彼此的工具和 RAG 引用。
    private final ThreadLocal<MutableTrace> current = new ThreadLocal<>();

    public ActiveTrace begin() {
        MutableTrace trace = new MutableTrace();
        current.set(trace);
        return new ActiveTrace(trace);
    }

    public void recordToolExecution(ToolExecution execution) {
        MutableTrace trace = current.get();
        if (trace == null) {
            return;
        }
        trace.tools.add(new AgentTrace.ToolCall(
                execution.request().name(),
                abbreviate(execution.result(), MAX_SUMMARY_LENGTH),
                execution.duration().toMillis(),
                execution.hasFailed() ? "FAILED" : "COMPLETED"));
    }

    public void recordRetrievedContent(ContentRetrieverResponseContext response) {
        MutableTrace trace = current.get();
        if (trace == null) {
            return;
        }
        for (Content content : response.contents()) {
            String text = content.textSegment().text();
            trace.ragReferences.add(new AgentTrace.RagReference(
                    sourceOf(content),
                    scoreOf(content),
                    abbreviate(text, MAX_EXCERPT_LENGTH)));
        }
    }

    private Double scoreOf(Content content) {
        Object score = content.metadata().get(ContentMetadata.SCORE);
        return score instanceof Number number ? number.doubleValue() : null;
    }

    private String sourceOf(Content content) {
        String source = content.textSegment().metadata().getString("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        String text = content.textSegment().text();
        int lineBreak = text.indexOf('\n');
        String firstLine = lineBreak >= 0 ? text.substring(0, lineBreak) : text;
        return firstLine.startsWith("来源：") ? firstLine.substring("来源：".length()) : "知识库片段";
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "无返回内容";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "…";
    }

    public final class ActiveTrace implements AutoCloseable {
        private final MutableTrace trace;

        private ActiveTrace(MutableTrace trace) {
            this.trace = trace;
        }

        public AgentTrace snapshot() {
            return new AgentTrace(List.copyOf(trace.tools), List.copyOf(trace.ragReferences));
        }

        @Override
        public void close() {
            // Servlet 线程会被复用；不清理会将本次轨迹泄漏到后续请求。
            current.remove();
        }
    }

    private static final class MutableTrace {
        private final List<AgentTrace.ToolCall> tools = new ArrayList<>();
        private final List<AgentTrace.RagReference> ragReferences = new ArrayList<>();
    }
}
