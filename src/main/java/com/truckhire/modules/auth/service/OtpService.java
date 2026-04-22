package com.truckhire.modules.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.auth.dto.RegisterRequest;
import com.truckhire.modules.auth.entity.PendingRegistration;
import com.truckhire.modules.auth.repository.PendingRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OtpService {

    private final PendingRegistrationRepository pendingRepo;
    private final ObjectMapper objectMapper;

    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    public String generateOtp() {
        SecureRandom random = new SecureRandom();
        return String.format("%06d", random.nextInt(1_000_000));
    }

    public void storePending(RegisterRequest request, String otp) {
        try {
            String payloadJson = objectMapper.writeValueAsString(request);
            String normalizedEmail = request.getEmail().toLowerCase().trim();

            PendingRegistration pending = pendingRepo.findByEmail(normalizedEmail)
                    .orElse(PendingRegistration.builder()
                            .email(normalizedEmail)
                            .build());

            pending.setPhone(request.getPhone().trim());
            pending.setOtpCode(otp);
            pending.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
            pending.setPayloadJson(payloadJson);
            pendingRepo.save(pending);

            log.debug("Pending registration upserted for email={}", normalizedEmail);
        } catch (Exception e) {
            throw new BusinessException("OTP_STORE_FAILED",
                    "Failed to store pending registration. Please try again.");
        }
    }

    public RegisterRequest validateAndConsume(String email, String otp) {
        String normalizedEmail = email.toLowerCase().trim();

        PendingRegistration pending = pendingRepo.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No pending registration found for this email. Please register again."));

        if (Instant.now().isAfter(pending.getExpiresAt())) {
            pendingRepo.delete(pending);
            throw new BusinessException("OTP_EXPIRED",
                    "OTP has expired. Please register again to receive a new code.");
        }

        if (!pending.getOtpCode().equals(otp)) {
            throw new BusinessException("OTP_INVALID",
                    "Incorrect OTP. Please check the code and try again.");
        }

        try {
            RegisterRequest originalRequest = objectMapper.readValue(
                    pending.getPayloadJson(), RegisterRequest.class);
            pendingRepo.delete(pending);
            log.info("OTP verified for email={}, pending registration consumed", normalizedEmail);
            return originalRequest;
        } catch (Exception e) {
            throw new BusinessException("OTP_PARSE_FAILED",
                    "Failed to process registration. Please register again.");
        }
    }

    @Transactional(readOnly = true)
    public boolean hasPendingRegistration(String email) {
        return pendingRepo.findByEmail(email.toLowerCase().trim()).isPresent();
    }

    @Transactional(readOnly = true)
    public void checkResendCooldown(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        pendingRepo.findByEmail(normalizedEmail).ifPresent(pending -> {
            long secondsSinceCreated = ChronoUnit.SECONDS.between(
                    pending.getCreatedAt(), Instant.now());
            if (secondsSinceCreated < RESEND_COOLDOWN_SECONDS) {
                long waitSeconds = RESEND_COOLDOWN_SECONDS - secondsSinceCreated;
                throw new BusinessException("OTP_COOLDOWN",
                        "Please wait " + waitSeconds + " second(s) before requesting a new OTP.");
            }
        });
    }

    public void updateOtpOnly(String email, String newOtp) {
        String normalizedEmail = email.toLowerCase().trim();
        PendingRegistration pending = pendingRepo.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No pending registration found for this email. Please register again."));

        pending.setOtpCode(newOtp);
        pending.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        pending.setCreatedAt(Instant.now());
        pendingRepo.save(pending);
    }

    public void updatePendingForResend(RegisterRequest request, String newOtp) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();
        PendingRegistration pending = pendingRepo.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No pending registration found for this email. Please register again."));

        try {
            String payloadJson = objectMapper.writeValueAsString(request);
            pending.setPhone(request.getPhone().trim());
            pending.setPayloadJson(payloadJson);
        } catch (Exception e) {
            throw new BusinessException("OTP_STORE_FAILED",
                    "Failed to update pending registration. Please try again.");
        }

        pending.setOtpCode(newOtp);
        pending.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        pending.setCreatedAt(Instant.now());
        pendingRepo.save(pending);
    }
}
