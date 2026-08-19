package com.ncwu.predictionservice.service.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ncwu.common.domain.vo.Result;
import com.ncwu.common.apis.iot_service.IotDataService;
import com.ncwu.predictionservice.agent.AgentAnswer;
import com.ncwu.predictionservice.agent.WaterAgent;
import com.ncwu.predictionservice.agent.WaterInsightAiService;
import com.ncwu.predictionservice.agent.DeviceQualityRate;
import com.ncwu.predictionservice.agent.TextSuggestion;
import com.ncwu.predictionservice.agent.WaterQualityMetrics;
import com.ncwu.predictionservice.agent.WaterUsageHistory;
import com.ncwu.predictionservice.agent.WaterUsagePrediction;
import com.ncwu.predictionservice.conversation.AgentChatResponse;
import com.ncwu.predictionservice.conversation.AgentConversation;
import com.ncwu.predictionservice.conversation.AgentMessage;
import com.ncwu.predictionservice.conversation.ConversationRepository;
import com.ncwu.predictionservice.conversation.LongTermMemoryService;
import com.ncwu.predictionservice.service.AiService;
import com.ncwu.predictionservice.domain.UsageBO;
import com.ncwu.predictionservice.domain.vo.UsageVO;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/3/5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final WaterAgent waterAgent;
    private final WaterInsightAiService waterInsightAiService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ConversationRepository> conversationRepositoryProvider;
    private final ObjectProvider<LongTermMemoryService> longTermMemoryServiceProvider;
    private final ObjectProvider<ChatMemoryStore> chatMemoryStoreProvider;

    private final IotDataService iotDataService;

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
                    response = suggestionOf(waterInsightAiService.suggestWaterUsage());
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
                    String res = suggestionOf(waterInsightAiService.suggestWaterQuality(
                            new WaterQualityMetrics(score, ph, ch, th)));
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
            String res = suggestionOf(waterInsightAiService.suggestDeviceQuality(new DeviceQualityRate(data)));
            redisTemplate.opsForValue().set("suggestionOfDeviceData", res, 240, TimeUnit.SECONDS);
            return Result.ok(res);
        }

    }

    @Override
    public Result<AgentChatResponse> chatWithAgent(String conversationId, String userId, String input) {
        Result<AgentConversation> conversationResult = resolveConversation(conversationId, userId);
        if (conversationResult.getData() == null) {
            return Result.fail(null, conversationResult.getMessage());
        }
        AgentConversation conversation = conversationResult.getData();

        RLock lock = redissonClient.getLock("agent:conversation:" + conversation.id());
        try {
            if (!lock.tryLock(5, 90, TimeUnit.SECONDS)) {
                return Result.fail(null, "该会话正在生成回复，请稍后重试");
            }
            Result<Void> recordResult = recordConversationUserMessage(conversation.id(), userId, input);
            if (!"200".equals(recordResult.getCode())) {
                return Result.fail(null, recordResult.getMessage());
            }
            AgentAnswer agentAnswer = waterAgent.chat(conversation.id(), input);
            String answer = answerOf(agentAnswer);
            completeConversationTurn(conversation.id(), answer);
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
    public Result<AgentConversation> resolveConversation(String conversationId, String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        LongTermMemoryService longTermMemoryService = longTermMemoryServiceProvider.getIfAvailable();
        if (conversationRepository == null || longTermMemoryService == null) {
            return Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务");
        }
        try {
            AgentConversation conversation = conversationId == null || conversationId.isBlank()
                    ? conversationRepository.create(userId)
                    : conversationRepository.findOwned(conversationId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在或无权限访问"));
            return Result.ok(conversation);
        } catch (RuntimeException exception) {
            return Result.fail(null, exception.getMessage());
        }
    }

    @Override
    public Result<Void> recordConversationUserMessage(String conversationId, String userId, String input) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        if (conversationRepository == null) {
            return Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务");
        }
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        conversationRepository.appendMessage(conversationId, "user", input);
        conversationRepository.setTitleFromFirstMessage(conversationId, input);
        return Result.ok(null);
    }

    @Override
    public void completeConversationTurn(String conversationId, String answer) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        LongTermMemoryService longTermMemoryService = longTermMemoryServiceProvider.getIfAvailable();
        if (conversationRepository == null || longTermMemoryService == null) {
            return;
        }
        conversationRepository.appendMessage(conversationId, "assistant", answer);
        longTermMemoryService.refreshIfNeeded(conversationId);
    }

    @Override
    public Result<AgentConversation> createConversation(String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        return conversationRepository == null
                ? Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务")
                : Result.ok(conversationRepository.create(userId));
    }

    @Override
    public Result<List<AgentConversation>> listConversations(String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        return conversationRepository == null
                ? Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务")
                : Result.ok(conversationRepository.findAllOwned(userId));
    }

    @Override
    public Result<List<AgentMessage>> listMessages(String conversationId, String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        if (conversationRepository == null) {
            return Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务");
        }
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        return Result.ok(conversationRepository.messages(conversationId));
    }

    @Override
    public Result<Void> clearConversationContext(String conversationId, String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        ChatMemoryStore chatMemoryStore = chatMemoryStoreProvider.getIfAvailable();
        if (conversationRepository == null || chatMemoryStore == null) {
            return Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务");
        }
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        chatMemoryStore.deleteMessages(conversationId);
        conversationRepository.resetSummary(conversationId);
        return Result.ok(null);
    }

    @Override
    public Result<Void> deleteConversation(String conversationId, String userId) {
        ConversationRepository conversationRepository = conversationRepositoryProvider.getIfAvailable();
        ChatMemoryStore chatMemoryStore = chatMemoryStoreProvider.getIfAvailable();
        if (conversationRepository == null || chatMemoryStore == null) {
            return Result.fail(null, "会话功能未启用；请使用 memory profile 启动服务");
        }
        if (conversationRepository.findOwned(conversationId, userId).isEmpty()) {
            return Result.fail(null, "会话不存在或无权限访问");
        }
        chatMemoryStore.deleteMessages(conversationId);
        conversationRepository.softDelete(conversationId);
        return Result.ok(null);
    }

    private Result<UsageVO> generateAndCachePrediction(List<Double> usage, int campus) {
        WaterUsagePrediction prediction = waterInsightAiService
                .predictTomorrowWaterUsage(new WaterUsageHistory(List.copyOf(usage)));
        double predictedValue = predictedUsageOf(prediction);
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

    private String answerOf(AgentAnswer result) {
        if (result == null || result.answer() == null || result.answer().isBlank()) {
            throw new IllegalStateException("模型未返回有效回答");
        }
        return result.answer().trim();
    }

    private String suggestionOf(TextSuggestion result) {
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new IllegalStateException("模型未返回有效建议");
        }
        return result.content().trim();
    }

    private double predictedUsageOf(WaterUsagePrediction result) {
        if (result == null || result.predictedUsage() == null
                || !Double.isFinite(result.predictedUsage()) || result.predictedUsage() < 0) {
            throw new IllegalStateException("模型未返回有效的用水预测值");
        }
        return result.predictedUsage();
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
