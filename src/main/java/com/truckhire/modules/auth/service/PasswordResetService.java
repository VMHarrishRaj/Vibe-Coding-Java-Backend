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

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;

    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    public void storePendingReset(String email, String otp) {
        String normalizedEmail = email.toLowerCase().trim();

        PasswordResetToken token = tokenRepository.findByEmail(normalizedEmail)
                .orElse(PasswordResetToken.builder()
                        .email(normalizedEmail)
                        .build());

        token.setOtpCode(otp);
        token.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);

        log.debug("Password reset token upserted for email={}", normalizedEmail);
    }

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
