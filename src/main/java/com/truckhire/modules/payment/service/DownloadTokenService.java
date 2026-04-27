package com.truckhire.modules.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived download tokens for unauthenticated PDF downloads.
 *
 * Mobile opens invoice PDFs via Linking.openURL (device browser) which cannot
 * send Authorization headers. The flow:
 *   1. Frontend calls POST /payments/{id}/download-token with JWT → gets a signed URL
 *   2. Frontend passes URL to Linking.openURL
 *   3. Browser hits GET /payments/download?token=... (no auth header needed)
 *   4. Backend validates the token from Redis and streams the PDF
 *
 * Key design:
 *   Key:   download:{uuid}
 *   Value: {transactionId}:{userId}
 *   TTL:   300 seconds (5 minutes) — reusable within window, auto-expires
 */
@Service
@RequiredArgsConstructor
public class DownloadTokenService {

    private static final String PREFIX = "download:";
    public static final int TTL_SECONDS = 300;

    private final RedisTemplate<String, String> redisTemplate;

    public String issueToken(UUID transactionId, UUID userId) {
        String token = UUID.randomUUID().toString();
        String value = transactionId + ":" + userId;
        redisTemplate.opsForValue().set(PREFIX + token, value, TTL_SECONDS, TimeUnit.SECONDS);
        return token;
    }

    /** Returns [transactionId, userId] or null if token is missing/expired. */
    public String[] resolveToken(String token) {
        String value = redisTemplate.opsForValue().get(PREFIX + token);
        if (value == null) return null;
        String[] parts = value.split(":", 2);
        return parts.length == 2 ? parts : null;
    }
}
