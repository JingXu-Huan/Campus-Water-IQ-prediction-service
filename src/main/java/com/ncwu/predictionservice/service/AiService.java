package com.ncwu.predictionservice.service;

import com.ncwu.common.domain.vo.Result;
import com.ncwu.predictionservice.domain.vo.UsageVO;
import com.ncwu.predictionservice.conversation.AgentChatResponse;
import com.ncwu.predictionservice.conversation.AgentConversation;
import com.ncwu.predictionservice.conversation.AgentMessage;
import java.util.List;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/3/4
 */
public interface AiService {
    Result<UsageVO> predictTomorrowWaterUsage(List<Double> usage, int campus);

    Result<String> suggestionOfWaterUsage();

    Result<String> suggestionOfWater(int score, double ph, double ch, double th);

    Result<String> suggestionOfDevice(Double data);

    Result<AgentChatResponse> chatWithAgent(String conversationId, String userId, String input);

    Result<AgentConversation> createConversation(String userId);

    Result<List<AgentConversation>> listConversations(String userId);

    Result<List<AgentMessage>> listMessages(String conversationId, String userId);

    Result<Void> clearConversationContext(String conversationId, String userId);

    Result<Void> deleteConversation(String conversationId, String userId);
}
