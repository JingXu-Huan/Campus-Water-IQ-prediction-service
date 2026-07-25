package com.ncwu.predictionservice.agent;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;

/** Streaming variant of the Water Agent. */
public interface WaterStreamingAgent {

    @SystemMessage(WaterAgent.SYSTEM_MESSAGE)
    TokenStream chat(@UserMessage String userInput);
}
