package com.truckhire.modules.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores a password reset OTP awaiting verification.
 *
 * LIFECYCLE:
 *   INSERT/UPDATE (upsert) — when /auth/forgot-password is called
 *   DELETE — when /auth/reset-password succeeds (OTP consumed)
 *
 * One row per email — upserted on each request so re-requests
 * overwrite the previous OTP rather than accumulating rows.
 *
 * Does NOT extend BaseAuditEntity — no soft-delete, no updatedAt.
 * createdAt is always reset on upsert to track the cooldown window
 * from the most recent send (not the original request).
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "otp_code", nullable = false, length = 6)
    private String otpCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
