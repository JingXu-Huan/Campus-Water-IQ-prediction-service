package com.ncwu.predictionservice.trace;

/** Agent 对话接口返回值，包含可供前端展示的执行轨迹。 */
public record AgentChatResponse(String answer, AgentTrace trace) {
}
