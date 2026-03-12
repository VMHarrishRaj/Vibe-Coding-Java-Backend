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

/**
 * OTP lifecycle management for the pre-registration verification flow.
 *
 * Responsibilities:
 *   - Generate cryptographically random 6-digit OTP
 *   - Store the pending registration payload in DB (upsert by email)
 *   - Validate OTP on verify — deletes the pending row on success
 *   - Enforce 1-minute cooldown between resend requests
 *
 * The pending_registrations table acts as a temporary store.
 * No user row exists until OTP is verified — that is the point of this service.
 *
 * Future: When Redis is active (Phase 10), replace DB storage with
 * Redis keys (otp:{email}) with TTL=600s, drop the pending_registrations table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OtpService {

    private final PendingRegistrationRepository pendingRepo;
    private final ObjectMapper objectMapper;

    private static final int OTP_EXPIRY_MINUTES = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    /**
     * Generate a cryptographically random 6-digit OTP.
     * SecureRandom is used instead of Random to ensure unpredictability.
     */
    public String generateOtp() {
        SecureRandom random = new SecureRandom();
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * Upsert a pending registration row with the given OTP.
     *
     * If a row for this email already exists (resend case), it is updated
     * in-place (same row, new OTP and expiry). If not, a new row is inserted.
     *
     * The created_at field is NOT updated on resend — it tracks when the first
     * request was made, which is what the cooldown check reads.
     */
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

    /**
     * Validate the OTP and consume the pending registration.
     *
     * Checks (in order):
     *   1. Pending row exists for the email
     *   2. OTP has not expired (expires_at > now)
     *   3. OTP code matches
     *
     * On success: deletes the pending row and returns the original RegisterRequest.
     * The caller (AuthService.verifyOtp) uses this to create the actual user.
     *
     * On any failure: throws BusinessException with an appropriate error code.
     */
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

        // OTP is valid — deserialize the stored payload and clean up the pending row
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

    /**
     * Check whether a pending registration exists for the given email.
     * Used by initiateRegistration to detect the re-register (OTP re-entry) path.
     */
    @Transactional(readOnly = true)
    public boolean hasPendingRegistration(String email) {
        return pendingRepo.findByEmail(email.toLowerCase().trim()).isPresent();
    }

    /**
     * Enforce a 1-minute cooldown between OTP resend requests.
     *
     * Reads created_at from the pending row (not updated on resend — tracks
     * the original request time, which resets only when OTP is fully consumed
     * or a fresh register is submitted).
     *
     * Actually: on resend we upsert the row. created_at is set @PrePersist only,
     * so it stays as the original creation time. This gives a true 60-second
     * window from the last resend or initial request.
     *
     * Wait — to track per-resend cooldown accurately, we update created_at on resend.
     * See storePendingForResend() which resets created_at.
     */
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

    /**
     * Update the pending row for a resend — resets created_at to now
     * so the cooldown window tracks from this resend, not the original request.
     */
    public void updatePendingForResend(String email, String newOtp) {
        String normalizedEmail = email.toLowerCase().trim();
        PendingRegistration pending = pendingRepo.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No pending registration found for this email. Please register again."));

        pending.setOtpCode(newOtp);
        pending.setExpiresAt(Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES));
        pending.setCreatedAt(Instant.now()); // reset cooldown window
        pendingRepo.save(pending);
    }
}
