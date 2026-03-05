package com.truckhire.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Auth Response DTO — returned after successful login or registration.
 *
 * Contains the JWT access token and minimal user identification.
 * The mobile app stores the accessToken and sends it
 * in the Authorization header on every subsequent request:
 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
 *
 * DESIGN DECISION (Phase 2 — modular approach):
 * Only the user ID is returned here. Personal data (name, email, phone)
 * will be available via GET /users/me in Phase 3 (User Management).
 * The JWT token itself already contains role and email in its claims,
 * so the client can decode those if needed before Phase 3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String tokenType;
    private long expiresIn; // seconds until token expires
    private UserInfo user;

    /**
     * Minimal user identification.
     * Phase 3 will add GET /users/me for full profile retrieval.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private String id;
    }
}
