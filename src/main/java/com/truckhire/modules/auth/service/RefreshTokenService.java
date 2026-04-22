package com.truckhire.modules.auth.service;

import com.truckhire.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Refresh token lifecycle management backed by Redis.
 *
 * WHY REFRESH TOKENS:
 *   Access tokens are short-lived (15 min in production). Without refresh tokens,
 *   users have to re-login every 15 minutes — terrible mobile UX.
 *   A refresh token is a long-lived credential (7 days) that lets the mobile app
 *   silently obtain a new access token without asking the user to log in again.
 *
 * KEY DESIGN:
 *   Key: refresh:{userId}:{jti}
 *   Value: userId (stored so we can validate ownership without decoding the JWT again)
 *   TTL: 7 days — Redis auto-expires it
 *
 *   The {userId} namespace in the key means we can do pattern-delete for logout-all:
 *   DEL refresh:{userId}:* clears every device session for that user.
 *
 * TOKEN ROTATION:
 *   Each time a refresh token is used, it is replaced with a brand-new one.
 *   The old JTI is deleted from Redis immediately. This prevents replay attacks:
 *   if an attacker steals a refresh token and tries to use it after the legitimate
 *   client already rotated it, the old JTI is gone — the request fails with 401.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX = "refresh:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JwtService jwtService;

    /**
     * Store a refresh token in Redis after login or registration.
     *
     * @param userId  The user's UUID
     * @param token   The refresh token string (JWT)
     */
    public void store(UUID userId, String token) {
        String jti = jwtService.getJtiFromToken(token);
        long ttl = jwtService.getRefreshTokenExpirationSeconds();
        String key = key(userId, jti);
        redisTemplate.opsForValue().set(key, userId.toString(), ttl, TimeUnit.SECONDS);
        log.debug("Refresh token stored: userId={}, jti={}", userId, jti);
    }

    /**
     * Validate a refresh token:
     *   1. JWT signature + expiry are valid (JwtService.validateToken)
     *   2. Token type is REFRESH (not an access token passed by mistake)
     *   3. The JTI exists in Redis (not already rotated or revoked)
     *
     * Returns the userId extracted from the token on success.
     * Throws INVALID_REFRESH_TOKEN on any failure.
     */
    public UUID validate(String token) {
        if (!jwtService.validateToken(token)) {
            throw new BusinessException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired.");
        }

        UUID userId = jwtService.getUserIdFromToken(token);
        String jti = jwtService.getJtiFromToken(token);
        String key = key(userId, jti);

        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            throw new BusinessException("INVALID_REFRESH_TOKEN",
                    "Refresh token has been revoked. Please log in again.");
        }

        return userId;
    }

    /**
     * Rotate: delete the old refresh token and return the new one.
     * Called during POST /auth/refresh — old token in, new token out.
     */
    public String rotate(String oldToken, UUID userId) {
        String oldJti = jwtService.getJtiFromToken(oldToken);
        redisTemplate.delete(key(userId, oldJti));

        String newToken = jwtService.generateRefreshToken(userId);
        store(userId, newToken);

        log.debug("Refresh token rotated: userId={}", userId);
        return newToken;
    }

    /**
     * Revoke a single refresh token (logout from one device).
     */
    public void revoke(String token, UUID userId) {
        String jti = jwtService.getJtiFromToken(token);
        redisTemplate.delete(key(userId, jti));
        log.debug("Refresh token revoked: userId={}, jti={}", userId, jti);
    }

    /**
     * Revoke ALL refresh tokens for a user (logout from all devices).
     * Uses Redis SCAN to find all keys matching refresh:{userId}:*
     */
    public void revokeAll(UUID userId) {
        String pattern = PREFIX + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("All refresh tokens revoked for userId={}, count={}", userId, keys.size());
        }
    }

    private String key(UUID userId, String jti) {
        return PREFIX + userId + ":" + jti;
    }
}
