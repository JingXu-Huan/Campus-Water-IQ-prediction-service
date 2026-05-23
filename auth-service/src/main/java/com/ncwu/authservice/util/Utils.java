package com.ncwu.authservice.util;


import com.ncwu.common.apis.warning_service.EmailServiceInterFace;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.jspecify.annotations.NonNull;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author jingxu
 * @version 1.0.0
 * @since 2026/2/11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Utils {

    @DubboReference(version = "1.0.0",interfaceClass = EmailServiceInterFace.class)
    private EmailServiceInterFace emailServiceInterFace;

    private final RocketMQTemplate rocketMQTemplate;

    public static String genUid(int type) {
        String prefix = "user_";
        switch (type) {
            case 1 -> prefix = "user_";
            case 2 -> prefix = "maintain";
            case 3 -> prefix = "root";
        }
        return prefix + UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10);
    }

    public static String genNickName() {
        return "User_" + UUID.randomUUID().toString().replaceAll("-", "").substring(0, 10);
    }

    public void sendValidCodeToMail(String toEmail, RedissonClient redissonClient, StringRedisTemplate redisTemplate) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter("Email:limiter");
        rateLimiter.setRate(RateType.OVERALL, 100, 1, RateIntervalUnit.SECONDS);
        if (rateLimiter.tryAcquire()) {
            String code = genValidCode();
            try {
                redisTemplate.opsForValue().set("Verify:EmailCode:" + toEmail, code, 5, TimeUnit.MINUTES);
                emailServiceInterFace.sendVerificationCode(toEmail, code);
            } catch (MessagingException e) {
                // 消息队列通知 - 邮件发送失败时发送到MQ进行重试
                log.error("验证码发送失败，{}，已发送到MQ重试", toEmail);
                rocketMQTemplate.convertAndSend("auth:email:failed",
                        java.util.Map.of("email", toEmail, "code", code));
            }
        }
    }

    public static @NonNull String genValidCode() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
    }
}
