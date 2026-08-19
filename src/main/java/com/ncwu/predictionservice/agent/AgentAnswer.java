package com.ncwu.predictionservice.agent;

/**
 * Agent 的机器可读最终答复。工具调用与 RAG 引用由服务端采集，不能由模型伪造。
 */
public record AgentAnswer(String answer) {
}
