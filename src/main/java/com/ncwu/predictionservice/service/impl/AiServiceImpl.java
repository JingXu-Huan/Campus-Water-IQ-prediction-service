package com.ncwu.predictionservice.service.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncwu.common.domain.vo.Result;
import com.ncwu.common.apis.iot_service.IotDataService;
import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.conversation.AgentChatResponse;
import com.ncwu.predictionservice.conversation.AgentConversation;
import com.ncwu.predictionservice.conversation.AgentMessage;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import com.ncwu.predictionservice.conversation.LongTermMemoryService;
import com.ncwu.predictionservice.service.AiService;
import com.ncwu.predictionservice.domain.UsageBO;
import com.ncwu.predictionservice.domain.vo.UsageVO;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.ncwu.predictionservice.system.Prompt.waterUseSuggestion;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/3/5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final ChatModel chatModel;
    private final WaterAgent waterAgent;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final LongTermMemoryService longTermMemoryService;
    private final ChatMemoryStore chatMemoryStore;

    @DubboReference(version = "1.0.0", timeout = 10000)
    private IotDataService iotDataService;

    String keyPrefix = "WaterPredictionUsage:";

    @Override
    public Result<UsageVO> predictTomorrowWaterUsage(List<Double> usage, int campus) {
        String json = redisTemplate.opsForValue().get(keyPrefix + campus);

        if (json == null || json.isEmpty()) {
            return generateAndCachePrediction(usage, campus);
        }

        UsageBO usageBO = parseCachedUsage(json);

        if (usageBO != null && !isCacheExpired(usageBO)) {
            return Result.ok(new UsageVO(campus, usageBO.getUsage()));
        }

        if (usageBO != null) {
            regeneratePredictionAsync(campus, usage);
            return Result.ok(new UsageVO(campus, usageBO.getUsage()));
        }

        return generateAndCachePrediction(usage, campus);
    }

    @Override
    public Result<String> suggestionOfWaterUsage() {
        String suggestion = redisTemplate.opsForValue().get("suggestion");
        if (suggestion == null) {
            RLock lock = redissonClient.getLock("suggestions");
            String response;
            try {
                if (lock.tryLock()) {
                    response = chatModel.chat(waterUseSuggestion);
                    redisTemplate.opsForValue().set("suggestion", response, 30, TimeUnit.MINUTES);
                } else return Result.ok("刷牙的时候记得把水龙头关掉哦");
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            return Result.ok(response);
        } else return Result.ok(suggestion);
    }

    @Override
    public Result<String> suggestionOfWater(int score, double ph, double ch, double th) {
        String cacheKey = "water_suggestion";
        String lockKey = "lock:" + cacheKey;

        // 先尝试从缓存获取
        String cachedResult = redisTemplate.opsForValue().get(cacheKey);
        if (cachedResult != null) {
            return Result.ok(cachedResult);
        }

        // 获取分布式锁
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 双重检查，防止其他线程已经设置了缓存
                    cachedResult = redisTemplate.opsForValue().get(cacheKey);
                    if (cachedResult != null) {
                        return Result.ok(cachedResult);
                    }
                    // 调用AI API
                    String res = chatModel
                            .chat("请根据我给你提供的水质信息，作出评价并且给出建议(50字)" +
                                    "：分数：" + score + "ph" + ph + "浊度" + th + "含氯量" + ch);
                    // 缓存结果，设置10分钟过期
                    redisTemplate.opsForValue().set(cacheKey, res, 10, TimeUnit.MINUTES);
                    return Result.ok(res);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 获取锁失败，返回默认建议或错误信息
                return Result.fail(null, "系统繁忙，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取锁时被中断: {}", e.getMessage());
            return Result.fail(null, "系统错误，请稍后再试");
        } catch (Exception e) {
            log.error("水质建议生成失败: {}", e.getMessage());
            return Result.fail(null, "服务暂时不可用，请稍后再试");
        }
    }

    @Override
    public Result<String> suggestionOfDevice(Double data) {
        String suggestion = redisTemplate.opsForValue().get("suggestionOfDeviceData");
        if (suggestion != null) {
            return Result.ok(suggestion);
        } else {
            String res = chatModel
                    .chat("我给你一个我们系统水质合格率的数据，你来写一句带有情绪价值的评语，不超过20字。水质合格率："
                            + data * 100 + "%");
            redisTemplate.opsForValue().set("suggestionOfDeviceData", res, 240, TimeUnit.SECONDS);
            return Result.ok(res);
        }

    }

    @Override
    public Result<AgentChatResponse> chatWithAgent(String conversationId, String userId, String input) {
        AgentConversation conversation;
        try {
            conversation = conversationId == null || conversationId.isBlank()
                    ? conversationRepository.create(userId)
                    : conversationRepository.findOwned(conversationId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权限访问"));
        } catch (RuntimeException exception) {
            return Result.fail(null, exception.getMessage());
        }

        RLock lock = redissonClient.getLock("agent:conversation:" + conversation.id());
        try {
            if (!lock.tryLock(5, 90, TimeUnit.SECONDS)) {
                return Result.fail(null, "该会话正在生成回复，请稍后重试");
            }
            conversationRepository.appendMessage(conversation.id(), "user", input);
            conversationRepository.setTitleFromFirstMessage(conversation.id(), input);
            String answer = waterAgent.chat(conversation.id(), input);
            conversationRepository.appendMessage(conversation.id(), "assistant", answer);
            longTermMemoryService.refreshIfNeeded(conversation.id());
            return Result.ok(new AgentChatResponse(conversation.id(), answer));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.fail(null, "会话请求被中断，请稍后重试");
        } catch (Exception e) {
            log.error("会话 {} 调用模型失败", conversation.id(), e);
            return Result.fail(null, "调用模型失败，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result<AgentConversation> createConversation(String userId) {
        return Result.ok(conversationRepository.create(userId));
    }

    @Override
    public Result<List<AgentConversation>> listConversations(String userId) {
        return Result.ok(conversationRepository.findAllOwned(userId));
    }

    @Override
    public Result<List<AgentMessage>> listMessages(String conversationId, String userId) {
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        return Result.ok(conversationRepository.messages(conversationId));
    }

    @Override
    public Result<Void> clearConversationContext(String conversationId, String userId) {
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        waterAgent.evictChatMemory(conversationId);
        chatMemoryStore.deleteMessages(conversationId);
        conversationRepository.resetSummary(conversationId);
        return Result.ok(null);
    }

    @Override
    public Result<Void> deleteConversation(String conversationId, String userId) {
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        waterAgent.evictChatMemory(conversationId);
        chatMemoryStore.deleteMessages(conversationId);
        conversationRepository.softDelete(conversationId);
        return Result.ok(null);
    }

    private double getRes(List<Double> usage) {
        try {
            String response = chatModel.chat(
                    "Predict the next water usage value based on this data: " + usage.toString() +
                            ". Return ONLY a single number without any explanation, text, or formatting. " +
                            "Example response: 209.25"
            );
            return Double.parseDouble(response.trim());
        } catch (NumberFormatException e) {
            log.error("Failed to parse AI response: {}", e.getMessage());
            throw new RuntimeException("Invalid AI response format", e);
        } catch (Exception e) {
            log.error("AI prediction failed: {}", e.getMessage());
            throw new RuntimeException("AI prediction failed", e);
        }
    }

    private Result<UsageVO> generateAndCachePrediction(List<Double> usage, int campus) {
        double predictedValue = getRes(usage);
        UsageBO usageBO = new UsageBO(predictedValue, LocalDateTime.now().plusMinutes(5));

        try {
            redisTemplate.opsForValue().set(keyPrefix + campus, objectMapper.writeValueAsString(usageBO));
        } catch (JsonProcessingException e) {
            log.error("Failed to cache prediction for campus {}: {}", campus, e.getMessage());
        }

        return Result.ok(new UsageVO(campus, predictedValue));
    }

    private UsageBO parseCachedUsage(String json) {
        try {
            return objectMapper.readValue(json, UsageBO.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse cached usage data: {}", e.getMessage());
            return null;
        }
    }

    private boolean isCacheExpired(UsageBO usageBO) {
        return usageBO.getExpireTime().isBefore(LocalDateTime.now());
    }

    @Async
    public void regeneratePredictionAsync(int campus, List<Double> usage) {
        String lockKey = "WaterUsage:" + campus;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, 300, TimeUnit.SECONDS)) {
                generateAndCachePrediction(usage, campus);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while trying to acquire lock for campus {}", campus);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
