package com.truckhire.modules.auth.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT Token Service — creates and validates JSON Web Tokens.
 *
 * ┌──────────────────────────────────────────────────────┐
 * │ HOW JWT AUTHENTICATION WORKS │
 * │ │
 * │ 1. User logs in with email + password │
 * │ 2. Server creates a JWT token containing: │
 * │ - sub (subject) = user ID │
 * │ - role = user's role name │
 * │ - exp = expiration timestamp │
 * │ 3. Server returns token to mobile app │
 * │ 4. Mobile app stores token locally │
 * │ 5. On every request, app sends: │
 * │ Authorization: Bearer eyJhbGciOiJIUzI1NiJ9... │
 * │ 6. JwtAuthFilter intercepts, validates token │
 * │ 7. If valid → request proceeds to controller │
 * │ If invalid → 401 Unauthorized │
 * └──────────────────────────────────────────────────────┘
 *
 * JWT structure (3 parts, base64-encoded, separated by dots):
 * HEADER.PAYLOAD.SIGNATURE
 * - Header: algorithm + type (HS256, JWT)
 * - Payload: user data (claims) — sub, role, exp
 * - Signature: HMAC-SHA256(header + payload, SECRET_KEY)
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    /**
     * Constructor injection.
     * 
     * @Value reads from application.yml → app.jwt.secret, etc.
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {

        // Create HMAC-SHA key from the secret string
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /**
     * Generate a JWT access token for a user.
     *
     * @param userId   The user's UUID (stored as "sub" claim)
     * @param email    The user's email (stored as "email" claim)
     * @param roleName The role name: ADMIN, OWNER, or RENTER
     * @return Signed JWT string
     */
    public String generateAccessToken(UUID userId, String email, String roleName) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString()) // who this token is for
                .claim("email", email) // custom claim
                .claim("role", roleName) // custom claim (used by JwtAuthFilter)
                .issuedAt(now) // when the token was created
                .expiration(expiry) // when the token expires
                .signWith(signingKey) // sign with HMAC-SHA256
                .compact(); // serialize to string
    }

    /**
     * Extract the user ID (subject) from a token.
     * Used by JwtAuthFilter to load the user.
     */
    public UUID getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extract the role from a token.
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
    }

    /**
     * Validate a JWT token.
     *
     * Returns false if:
     * - Token is malformed
     * - Token has expired
     * - Signature doesn't match (token was tampered with)
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT expired: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("JWT malformed: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("JWT signature invalid: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT token is empty: {}", ex.getMessage());
        }
        return false;
    }

    /**
     * Get the access token expiration in seconds (for the API response).
     */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    // ── Private helper ──

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
