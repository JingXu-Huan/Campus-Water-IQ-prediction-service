package com.ncwu.predictionservice.trace;

import java.util.List;

/** 单次 Agent 调用的客户端安全摘要。 */
public record AgentTrace(List<ToolCall> tools, List<RagReference> ragReferences) {

    public record ToolCall(String name, String resultSummary, long durationMs, String status) {
    }

    public record RagReference(String source, Double score, String excerpt) {
    }
}
