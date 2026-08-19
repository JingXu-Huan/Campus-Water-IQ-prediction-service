package com.ncwu.predictionservice.conversation;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@Profile("memory")
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "agent:memory:";

    private final StringRedisTemplate redisTemplate;

    @Value("${agent.memory.redis-ttl-hours:24}")
    private long redisTtlHours;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (RuntimeException exception) {
            log.warn("无法读取会话 {} 的 Redis 上下文，将重新开始窗口记忆", memoryId, exception);
            redisTemplate.delete(key(memoryId));
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // MessageWindowChatMemory 已完成窗口裁剪；此处只保存其给出的完整上下文快照。
        String json = ChatMessageSerializer.messagesToJson(messages);
        cache(memoryId, json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private void cache(Object memoryId, String json) {
        // 每轮对话刷新 TTL：活跃会话保留上下文，已废弃会话不会无限占用 Redis。
        redisTemplate.opsForValue().set(key(memoryId), json, Duration.ofHours(redisTtlHours));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
