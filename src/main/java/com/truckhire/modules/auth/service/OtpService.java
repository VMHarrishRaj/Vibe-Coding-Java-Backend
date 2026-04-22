package com.truckhire.modules.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.auth.dto.RegisterRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * OTP lifecycle management for the pre-registration verification flow.
 *
 * Backed by Redis instead of the old pending_registrations DB table.
 * Each pending registration is stored as two Redis keys:
 *
 *   otp:reg:payload:{email}  → JSON of RegisterRequest, TTL = 10 min
 *   otp:reg:code:{email}     → OTP code, TTL = 10 min
 *   otp:reg:cooldown:{email} → marker for 60-second resend cooldown, TTL = 60s
 *
 * Redis auto-expires all keys — no cron jobs, no table bloat.
 * If the server restarts, pending OTPs are lost — user just requests a new one.
 * This is acceptable: the old DB table had the same TTL semantics, just messier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final String PAYLOAD_PREFIX  = "otp:reg:payload:";
    private static final String CODE_PREFIX     = "otp:reg:code:";
    private static final String COOLDOWN_PREFIX = "otp:reg:cooldown:";

    private static final long OTP_TTL_SECONDS      = 600; // 10 minutes
    private static final long COOLDOWN_TTL_SECONDS = 60;  // 1 minute

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public String generateOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    /**
     * Store (or overwrite) the pending registration payload and OTP in Redis.
     * Called on first register and on re-register (user came back to the same screen).
     */
    public void storePending(RegisterRequest request, String otp) {
        String email = normalize(request.getEmail());
        try {
            String payloadJson = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set(PAYLOAD_PREFIX + email, payloadJson, OTP_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(CODE_PREFIX + email, otp, OTP_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(COOLDOWN_PREFIX + email, "1", COOLDOWN_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("Pending registration stored in Redis for email={}", email);
        } catch (Exception e) {
            throw new BusinessException("OTP_STORE_FAILED", "Failed to store pending registration. Please try again.");
        }
    }

    /**
     * Validate the OTP and consume the pending registration.
     * Returns the original RegisterRequest payload on success.
     * Deletes all Redis keys for this email.
     */
    public RegisterRequest validateAndConsume(String email, String otp) {
        String key = normalize(email);

        String storedPayload = redisTemplate.opsForValue().get(PAYLOAD_PREFIX + key);
        String storedOtp     = redisTemplate.opsForValue().get(CODE_PREFIX + key);

        if (storedOtp == null || storedPayload == null) {
            throw new BusinessException("OTP_NOT_FOUND",
                    "No pending registration found for this email. Please register again.");
        }

        if (!storedOtp.equals(otp)) {
            throw new BusinessException("OTP_INVALID", "Incorrect OTP. Please check the code and try again.");
        }

        try {
            RegisterRequest original = objectMapper.readValue(storedPayload, RegisterRequest.class);
            // Consume — delete all keys
            redisTemplate.delete(PAYLOAD_PREFIX + key);
            redisTemplate.delete(CODE_PREFIX + key);
            redisTemplate.delete(COOLDOWN_PREFIX + key);
            log.info("OTP verified and consumed for email={}", key);
            return original;
        } catch (Exception e) {
            throw new BusinessException("OTP_PARSE_FAILED", "Failed to process registration. Please register again.");
        }
    }

    public boolean hasPendingRegistration(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(PAYLOAD_PREFIX + normalize(email)));
    }

    /**
     * Enforce a 60-second cooldown between resend requests.
     * The cooldown key is set (or reset) every time an OTP is sent.
     * When the key exists in Redis, the cooldown is still active.
     */
    public void checkResendCooldown(String email) {
        String key = COOLDOWN_PREFIX + normalize(email);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw new BusinessException("OTP_COOLDOWN",
                    "Please wait " + ttl + " second(s) before requesting a new OTP.");
        }
    }

    /** Re-register path: refresh payload + OTP, reset cooldown. */
    public void updatePendingForResend(RegisterRequest request, String newOtp) {
        storePending(request, newOtp);
    }

    /** Resend path (user already on OTP screen): refresh OTP only, reset cooldown. */
    public void updateOtpOnly(String email, String newOtp) {
        String key = normalize(email);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(PAYLOAD_PREFIX + key))) {
            throw new BusinessException("OTP_NOT_FOUND",
                    "No pending registration found for this email. Please register again.");
        }
        redisTemplate.opsForValue().set(CODE_PREFIX + key, newOtp, OTP_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + key, "1", COOLDOWN_TTL_SECONDS, TimeUnit.SECONDS);
    }

    private String normalize(String email) {
        return email.toLowerCase().trim();
    }
}
