package com.truckhire.modules.auth.service;

import com.truckhire.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * OTP lifecycle management for the forgot-password flow.
 *
 * Backed by Redis instead of the old password_reset_tokens DB table.
 *
 *   otp:pwd:code:{email}     → OTP code, TTL = 10 min
 *   otp:pwd:cooldown:{email} → resend cooldown marker, TTL = 60s
 *
 * Redis auto-expires both keys. No table, no cron, no bloat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String CODE_PREFIX     = "otp:pwd:code:";
    private static final String COOLDOWN_PREFIX = "otp:pwd:cooldown:";

    private static final long OTP_TTL_SECONDS      = 600;
    private static final long COOLDOWN_TTL_SECONDS = 60;

    private final RedisTemplate<String, String> redisTemplate;

    public void storePendingReset(String email, String otp) {
        String key = normalize(email);
        redisTemplate.opsForValue().set(CODE_PREFIX + key, otp, OTP_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + key, "1", COOLDOWN_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("Password reset OTP stored in Redis for email={}", key);
    }

    public void checkResendCooldown(String email) {
        String key = COOLDOWN_PREFIX + normalize(email);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw new BusinessException("OTP_COOLDOWN",
                    "Please wait " + ttl + " second(s) before requesting a new code.");
        }
    }

    public void validateAndConsume(String email, String otp) {
        String key = normalize(email);
        String stored = redisTemplate.opsForValue().get(CODE_PREFIX + key);

        if (stored == null) {
            throw new BusinessException("OTP_NOT_FOUND",
                    "No password reset request found for this email. Please request a new code.");
        }

        if (!stored.equals(otp)) {
            throw new BusinessException("OTP_INVALID", "Incorrect code. Please check and try again.");
        }

        redisTemplate.delete(CODE_PREFIX + key);
        redisTemplate.delete(COOLDOWN_PREFIX + key);
        log.info("Password reset OTP validated and consumed for email={}", key);
    }

    private String normalize(String email) {
        return email.toLowerCase().trim();
    }
}
