package com.ncwu.predictionservice.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ncwu.predictionservice.conversation.entity.AgentConversationEntity;
import com.ncwu.predictionservice.conversation.entity.AgentMessageEntity;
import com.ncwu.predictionservice.conversation.mapper.AgentConversationMapper;
import com.ncwu.predictionservice.conversation.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话读写仓储。
 * 使用 MyBatis-Plus 的 Mapper 与 Lambda Wrapper
 */
@Repository
@Profile("memory")
@RequiredArgsConstructor
public class ConversationRepository {

    private static final String SUMMARY_PREFIX = "\n\n以下是该会话已确认的长期记忆摘要。将其作为背景信息使用；"
            + "若与用户本轮明确表达冲突，以用户本轮表达为准：\n";

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    public AgentConversation create(String userId) {
        AgentConversationEntity entity = new AgentConversationEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setTitle("新对话");
        entity.setSummarizedMessageCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastActiveAt(LocalDateTime.now());
        conversationMapper.insert(entity);
        return toConversation(entity);
    }

    public Optional<AgentConversation> findOwned(String conversationId, String userId) {
        return Optional.ofNullable(conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .eq(AgentConversationEntity::getUserId, userId)
                .isNull(AgentConversationEntity::getDeletedAt)))
                .map(this::toConversation);
    }

    public List<AgentConversation> findAllOwned(String userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversationEntity>()
                        .eq(AgentConversationEntity::getUserId, userId)
                        .isNull(AgentConversationEntity::getDeletedAt)
                        .orderByDesc(AgentConversationEntity::getLastActiveAt))
                .stream()
                .map(this::toConversation)
                .toList();
    }

    public void appendMessage(String conversationId, String role, String content) {
        AgentMessageEntity message = new AgentMessageEntity();
        message.setConversationId(uuid(conversationId));
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, message.getConversationId())
                .set(AgentConversationEntity::getLastActiveAt, LocalDateTime.now()));
    }

    public void setTitleFromFirstMessage(String conversationId, String input) {
        String title = input.replaceAll("\\s+", " ").trim();
        if (title.length() > 48) {
            title = title.substring(0, 48) + "…";
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .eq(AgentConversationEntity::getTitle, "新对话")
                .set(AgentConversationEntity::getTitle, title.isEmpty() ? "新对话" : title));
    }

    public List<AgentMessage> messages(String conversationId) {
        return messageEntities(conversationId).stream().map(this::toMessage).toList();
    }

    public List<AgentMessage> messagesAfter(String conversationId, int offset) {
        // 摘要记录的是已处理消息数量而非数据库主键，因此先按稳定顺序查询后再跳过该数量。
        return messageEntities(conversationId).stream().skip(offset).map(this::toMessage).toList();
    }

    public int messageCount(String conversationId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, uuid(conversationId)));
        return count == null ? 0 : Math.toIntExact(count);
    }

    public int summarizedMessageCount(String conversationId) {
        AgentConversationEntity entity = conversationMapper.selectById(uuid(conversationId));
        return entity == null || entity.getSummarizedMessageCount() == null
                ? 0 : entity.getSummarizedMessageCount();
    }

    public void updateSummary(String conversationId, String summary, int summarizedMessageCount) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .set(AgentConversationEntity::getSummary, summary)
                .set(AgentConversationEntity::getSummarizedMessageCount, summarizedMessageCount)
                .set(AgentConversationEntity::getLastActiveAt, LocalDateTime.now()));
    }

    /** 标记已由记忆决策模型评估的消息，避免无长期价值的内容被反复发送给模型。 */
    public void markMessagesReviewed(String conversationId, int reviewedMessageCount) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .set(AgentConversationEntity::getSummarizedMessageCount, reviewedMessageCount)
                .set(AgentConversationEntity::getLastActiveAt, LocalDateTime.now()));
    }

    public String summary(String conversationId) {
        AgentConversationEntity entity = conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .isNull(AgentConversationEntity::getDeletedAt));
        return entity == null || entity.getSummary() == null ? "" : entity.getSummary();
    }

    public void resetSummary(String conversationId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .set(AgentConversationEntity::getSummary, null)
                .set(AgentConversationEntity::getSummarizedMessageCount, 0));
    }

    public String longTermContext(String conversationId) {
        AgentConversationEntity entity = conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .isNull(AgentConversationEntity::getDeletedAt));
        if (entity == null || entity.getSummary() == null || entity.getSummary().isBlank()) {
            return "";
        }
        return SUMMARY_PREFIX + entity.getSummary();
    }

    public void softDelete(String conversationId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversationEntity>()
                .eq(AgentConversationEntity::getId, uuid(conversationId))
                .set(AgentConversationEntity::getDeletedAt, LocalDateTime.now()));
    }

    private List<AgentMessageEntity> messageEntities(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessageEntity>()
                .eq(AgentMessageEntity::getConversationId, uuid(conversationId))
                .orderByAsc(AgentMessageEntity::getId));
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private AgentConversation toConversation(AgentConversationEntity entity) {
        return new AgentConversation(entity.getId().toString(), entity.getTitle(), entity.getSummary(),
                entity.getCreatedAt(), entity.getLastActiveAt());
    }

    private AgentMessage toMessage(AgentMessageEntity entity) {
        return new AgentMessage(entity.getId(), entity.getRole(), entity.getContent(), entity.getCreatedAt());
    }
}
