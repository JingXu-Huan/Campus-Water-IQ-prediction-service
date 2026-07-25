package com.ncwu.predictionservice.conversation;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Slf4j
@Service
@Profile("memory")
@RequiredArgsConstructor
public class LongTermMemoryService {

    private final ConversationRepository conversationRepository;
    private final ChatModel chatModel;

    @Value("${agent.memory.summary-after-messages:12}")
    private int summaryAfterMessages;

    public void refreshIfNeeded(String conversationId) {
        int summarizedCount = conversationRepository.summarizedMessageCount(conversationId);
        int totalCount = conversationRepository.messageCount(conversationId);
        if (totalCount - summarizedCount < summaryAfterMessages) {
            return;
        }

        List<AgentMessage> newMessages = conversationRepository.messagesAfter(conversationId, summarizedCount);
        String oldSummary = conversationRepository.longTermContext(conversationId);
        String transcript = newMessages.stream()
                .map(message -> message.role() + "：" + message.content())
                .reduce("", (left, right) -> left + "\n" + right);

        try {
            String summary = chatModel.chat("""
                    请把下面的校园水务助手会话压缩为可供后续对话使用的长期记忆。
                    仅保留用户身份/偏好、已确认的地点和设备、数据结论、未完成任务与约束；
                    不要编造信息，不要输出寒暄，不超过 400 个中文字符。

                    已有摘要：
                    """ + oldSummary + "\n\n新增消息：" + transcript);
            conversationRepository.updateSummary(conversationId, summary.trim(), totalCount);
        } catch (RuntimeException exception) {
            // 摘要失败不能影响用户本轮已经完成的回答；下次仍会尝试压缩同一批消息。
            log.warn("会话 {} 的长期记忆摘要更新失败", conversationId, exception);
        }
    }
}
