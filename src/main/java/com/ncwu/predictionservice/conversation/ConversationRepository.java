package com.ncwu.predictionservice.conversation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConversationRepository {

    private static final String SUMMARY_PREFIX = "\n\n以下是该会话已确认的长期记忆摘要。将其作为背景信息使用；"
            + "若与用户本轮明确表达冲突，以用户本轮表达为准：\n";

    private final JdbcTemplate jdbcTemplate;

    public AgentConversation create(String userId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (id, user_id, title, created_at, last_active_at)
                VALUES (CAST(? AS uuid), ?, '新对话', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, userId);
        return findOwned(id, userId).orElseThrow();
    }

    public Optional<AgentConversation> findOwned(String conversationId, String userId) {
        List<AgentConversation> conversations = jdbcTemplate.query("""
                        SELECT id::text, title, summary, created_at, last_active_at
                        FROM agent_conversation
                        WHERE id = CAST(? AS uuid) AND user_id = ? AND deleted_at IS NULL
                        """,
                conversationRowMapper(), conversationId, userId);
        return conversations.stream().findFirst();
    }

    public List<AgentConversation> findAllOwned(String userId) {
        return jdbcTemplate.query("""
                        SELECT id::text, title, summary, created_at, last_active_at
                        FROM agent_conversation
                        WHERE user_id = ? AND deleted_at IS NULL
                        ORDER BY last_active_at DESC
                        """,
                conversationRowMapper(), userId);
    }

    public void appendMessage(String conversationId, String role, String content) {
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, role, content, created_at)
                VALUES (CAST(? AS uuid), ?, ?, CURRENT_TIMESTAMP)
                """, conversationId, role, content);
        jdbcTemplate.update("""
                UPDATE agent_conversation SET last_active_at = CURRENT_TIMESTAMP
                WHERE id = CAST(? AS uuid)
                """, conversationId);
    }

    public void setTitleFromFirstMessage(String conversationId, String input) {
        String title = input.replaceAll("\\s+", " ").trim();
        if (title.length() > 48) {
            title = title.substring(0, 48) + "…";
        }
        jdbcTemplate.update("""
                UPDATE agent_conversation SET title = ?
                WHERE id = CAST(? AS uuid) AND title = '新对话'
                """, title.isEmpty() ? "新对话" : title, conversationId);
    }

    public List<AgentMessage> messages(String conversationId) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, created_at FROM agent_message
                        WHERE conversation_id = CAST(? AS uuid)
                        ORDER BY id
                        """,
                messageRowMapper(), conversationId);
    }

    public List<AgentMessage> messagesAfter(String conversationId, int offset) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, created_at FROM agent_message
                        WHERE conversation_id = CAST(? AS uuid)
                        ORDER BY id OFFSET ?
                        """,
                messageRowMapper(), conversationId, offset);
    }

    public int messageCount(String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_message WHERE conversation_id = CAST(? AS uuid)
                """, Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    public int summarizedMessageCount(String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT summarized_message_count FROM agent_conversation WHERE id = CAST(? AS uuid)
                """, Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    public void updateSummary(String conversationId, String summary, int summarizedMessageCount) {
        jdbcTemplate.update("""
                UPDATE agent_conversation
                SET summary = ?, summarized_message_count = ?, last_active_at = CURRENT_TIMESTAMP
                WHERE id = CAST(? AS uuid)
                """, summary, summarizedMessageCount, conversationId);
    }

    public void resetSummary(String conversationId) {
        jdbcTemplate.update("""
                UPDATE agent_conversation SET summary = NULL, summarized_message_count = 0
                WHERE id = CAST(? AS uuid)
                """, conversationId);
    }

    public String longTermContext(String conversationId) {
        List<String> summaries = jdbcTemplate.query("""
                        SELECT summary FROM agent_conversation
                        WHERE id = CAST(? AS uuid) AND deleted_at IS NULL
                        """, (rs, rowNum) -> rs.getString(1), conversationId);
        if (summaries.isEmpty() || summaries.getFirst() == null || summaries.getFirst().isBlank()) {
            return "";
        }
        return SUMMARY_PREFIX + summaries.getFirst();
    }

    public void softDelete(String conversationId) {
        jdbcTemplate.update("""
                UPDATE agent_conversation SET deleted_at = CURRENT_TIMESTAMP
                WHERE id = CAST(? AS uuid)
                """, conversationId);
    }

    private RowMapper<AgentConversation> conversationRowMapper() {
        return (rs, rowNum) -> new AgentConversation(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class));
    }

    private RowMapper<AgentMessage> messageRowMapper() {
        return (rs, rowNum) -> new AgentMessage(
                rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class));
    }
}
