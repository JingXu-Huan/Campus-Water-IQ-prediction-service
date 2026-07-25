package com.ncwu.predictionservice.trace;

import java.util.List;

/** A client-safe summary of a single Agent invocation. */
public record AgentTrace(List<ToolCall> tools, List<RagReference> ragReferences) {

    public record ToolCall(String name, String resultSummary, long durationMs, String status) {
    }

    public record RagReference(String source, Double score, String excerpt) {
    }
}
