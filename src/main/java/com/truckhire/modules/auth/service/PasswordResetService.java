package com.truckhire.modules.auth.service;

import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.auth.entity.PasswordResetToken;
import com.truckhire.modules.auth.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * OTP lifecycle management for the forgot-password flow.
 *
 * Responsibilities:
 *   - Store (upsert) a password reset OTP in password_reset_tokens
 *   - Enforce a 1-minute cooldown between resend requests
 *   - Validate the OTP and delete the row on success (consume)
 *
 * One row per email — upserted on each request, deleted on success.
 * createdAt is always reset on upsert to track the cooldown window
 * from the most recent send.
 *
 * Future: When Redis is active (Phase 10), replace DB storage with
 * Redis keys (pwd_reset:{email}) with TTL=600s.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;

    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    /**
     * Upsert a password reset token row with the given OTP.
     * Always resets createdAt so the cooldown window tracks from this send.
     */
    public void storePendingReset(String email, String otp) {
        String normalizedEmail = email.toLowerCase().trim();

        PasswordResetToken token = tokenRepository.findByEmail(normalizedEmail)
                .orElse(PasswordResetToken.builder()
                        .email(normalizedEmail)
                        .build());

        token.setOtpCode(otp);
        token.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        token.setCreatedAt(Instant.now()); // always reset to track cooldown from this send
        tokenRepository.save(token);

        log.debug("Password reset token upserted for email={}", normalizedEmail);
    }

    /**
     * Enforce a 1-minute cooldown between forgot-password requests.
     * Throws OTP_COOLDOWN if less than 60 seconds since the last request.
     */
    @Transactional(readOnly = true)
    public void checkResendCooldown(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        tokenRepository.findByEmail(normalizedEmail).ifPresent(token -> {
            long secondsSince = ChronoUnit.SECONDS.between(token.getCreatedAt(), Instant.now());
            if (secondsSince < RESEND_COOLDOWN_SECONDS) {
                long waitSeconds = RESEND_COOLDOWN_SECONDS - secondsSince;
                throw new BusinessException("OTP_COOLDOWN",
                        "Please wait " + waitSeconds + " second(s) before requesting a new code.");
            }
        });
    }

    /**
     * Validate the OTP and consume (delete) the token on success.
     *
     * Checks (in order):
     *   1. Token row exists for the email
     *   2. OTP has not expired (expires_at > now)
     *   3. OTP code matches
     *
     * On success: deletes the token row.
     * On failure: throws BusinessException with an appropriate error code.
     */
    public void validateAndConsume(String email, String otp) {
        String normalizedEmail = email.toLowerCase().trim();

        PasswordResetToken token = tokenRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No password reset request found for this email. Please request a new code."));

        if (Instant.now().isAfter(token.getExpiresAt())) {
            tokenRepository.delete(token);
            throw new BusinessException("OTP_EXPIRED",
                    "Reset code has expired. Please request a new one.");
        }

        if (!token.getOtpCode().equals(otp)) {
            throw new BusinessException("OTP_INVALID",
                    "Incorrect code. Please check and try again.");
        }

        tokenRepository.delete(token);
        log.info("Password reset OTP validated and consumed for email={}", normalizedEmail);
    }
}
