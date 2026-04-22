package com.truckhire.modules.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * JWT blacklist backed by Redis.
 *
 * WHY THIS EXISTS:
 *   JWT tokens are stateless — once issued, the server cannot "cancel" them.
 *   If a user logs out or changes their password, their old token is still
 *   cryptographically valid until it expires. The blacklist solves this:
 *   we store the token's JTI (unique JWT ID) in Redis for exactly as long
 *   as the token would have lived. The JwtAuthFilter checks this before
 *   trusting any token.
 *
 * KEY DESIGN:
 *   Key: jwt:blacklist:{jti}
 *   Value: "1" (just a marker — we only need key existence)
 *   TTL: same as the token's remaining lifetime — Redis auto-expires it
 *
 * This means the blacklist never grows unboundedly. Every entry expires
 * exactly when the corresponding token would have expired anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String PREFIX = "jwt:blacklist:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Add a token's JTI to the blacklist.
     * TTL is set to the token's remaining lifetime so the entry auto-expires.
     *
     * @param jti          The JWT ID claim from the token
     * @param ttlSeconds   How long until this token would naturally expire
     */
    public void blacklist(String jti, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return; // already expired — no point storing it
        }
        redisTemplate.opsForValue().set(PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
        log.debug("Token blacklisted: jti={}, ttl={}s", jti, ttlSeconds);
    }

    /**
     * Check whether a token's JTI is on the blacklist.
     * Called by JwtAuthFilter on every authenticated request.
     */
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + jti));
    }
}
