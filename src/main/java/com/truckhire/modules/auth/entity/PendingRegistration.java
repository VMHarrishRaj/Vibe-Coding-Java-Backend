package com.truckhire.modules.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores a registration payload awaiting OTP verification.
 *
 * LIFECYCLE:
 *   INSERT — when /auth/register is called (OTP generated + sent)
 *   UPDATE — when /auth/resend-otp is called (new OTP, new expiry)
 *   DELETE — when /auth/verify-otp succeeds (account created)
 *
 * Expired rows (expires_at < NOW()) are benign stale data and
 * are deleted lazily on next register attempt for the same email.
 *
 * Does NOT extend BaseAuditEntity — no soft-delete, no updatedAt.
 * Lifecycle is simple: insert → (update) → delete.
 */
@Entity
@Table(name = "pending_registrations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "otp_code", nullable = false, length = 6)
    private String otpCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Full RegisterRequest serialized as JSON — re-parsed on verify to create the user. */
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
