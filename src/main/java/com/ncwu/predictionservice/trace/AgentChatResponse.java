package com.ncwu.predictionservice.trace;

/** Response returned by the Agent chat endpoint, including the observable execution trace. */
public record AgentChatResponse(String answer, AgentTrace trace) {
}
