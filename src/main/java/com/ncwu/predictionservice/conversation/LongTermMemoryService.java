package com.ncwu.predictionservice.conversation;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Slf4j
@Service
@Profile("memory")
@RequiredArgsConstructor
public class LongTermMemoryService {

    private static final String NO_UPDATE = "NO_UPDATE";

    private final ConversationRepository conversationRepository;
    private final ChatModel chatModel;

    public void refreshIfNeeded(String conversationId) {
        int summarizedCount = conversationRepository.summarizedMessageCount(conversationId);
        int totalCount = conversationRepository.messageCount(conversationId);
        if (totalCount == summarizedCount) {
            return;
        }

        List<AgentMessage> newMessages = conversationRepository.messagesAfter(conversationId, summarizedCount);
        String oldSummary = conversationRepository.summary(conversationId);
        String transcript = newMessages.stream()
                .map(message -> message.role() + "：" + message.content())
                .reduce("", (left, right) -> left + "\n" + right);

        try {
            String decision = chatModel.chat("""
                    你是校园水务助手的长期记忆决策器。判断“新增消息”中是否出现值得跨后续对话保留的信息。

                    只有以下信息值得写入记忆：用户身份或稳定偏好、已确认的校区/楼宇/设备、需要持续跟进的任务、
                    已确认且仍有价值的数据结论、长期约束或明确决定。
                    闲聊、一次性问答、未经确认的猜测、工具调用过程和可由实时接口重新获得的数据都不应记忆。

                    若无需更新，必须且只能输出：NO_UPDATE
                    若需要更新，输出合并“已有记忆”和“新增消息”后的完整长期记忆摘要；
                    不要输出标题、解释、Markdown 或其他文字，最多 400 个中文字符，不要编造信息。

                    已有记忆：
                    """ + oldSummary + "\n\n新增消息：" + transcript);
            if (NO_UPDATE.equals(decision.trim())) {
                conversationRepository.markMessagesReviewed(conversationId, totalCount);
                log.debug("会话 {} 的新增消息无需写入长期记忆", conversationId);
            } else {
                conversationRepository.updateSummary(conversationId, decision.trim(), totalCount);
                log.debug("会话 {} 的长期记忆已更新", conversationId);
            }
        } catch (RuntimeException exception) {
            // 决策失败不能影响用户本轮已经完成的回答；下次仍会尝试评估同一批消息。
            log.warn("会话 {} 的长期记忆决策失败", conversationId, exception);
        }
    }
}
