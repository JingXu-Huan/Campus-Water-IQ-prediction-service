package com.ncwu.predictionservice.conversation;

import java.time.LocalDateTime;

public record AgentMessage(
        long id,
        String role,
        String content,
        LocalDateTime createdAt
) {
}
