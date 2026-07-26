package com.ncwu.predictionservice.agent;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.MemoryId;

/** Water Agent 的流式响应版本。 */
public interface WaterStreamingAgent {

    @SystemMessage(WaterAgent.SYSTEM_MESSAGE)
    TokenStream chat(@MemoryId String conversationId, @UserMessage String userInput);
}
