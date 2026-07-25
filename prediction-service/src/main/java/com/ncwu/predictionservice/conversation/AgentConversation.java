package com.ncwu.predictionservice.conversation;

import java.time.LocalDateTime;

public record AgentConversation(
        String id,
        String title,
        String summary,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt
) {
}
