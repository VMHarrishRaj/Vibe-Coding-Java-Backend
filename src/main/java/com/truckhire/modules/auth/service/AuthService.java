package com.truckhire.modules.auth.service;

import com.truckhire.common.email.EmailSender;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.auth.dto.*;
import com.truckhire.modules.user.entity.Role;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.entity.UserStatus;
import com.truckhire.modules.user.repository.RoleRepository;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Auth Service — handles registration (OTP-gated) and login.
 *
 * REGISTRATION FLOW (two-step, OTP-gated):
 *
 *   Step 1 — initiateRegistration (POST /auth/register):
 *     - Validate email/phone not already taken
 *     - Generate 6-digit OTP
 *     - Store RegisterRequest payload + OTP in pending_registrations
 *     - Send OTP to email (console in dev, SMTP in prod)
 *     - Return OtpSentResponse (no account created yet)
 *
 *   Step 2 — verifyOtp (POST /auth/verify-otp):
 *     - Validate OTP via OtpService (checks expiry + code match)
 *     - Re-check email/phone uniqueness (edge case: race condition)
 *     - Create user account (same logic as old register())
 *     - Return JWT in AuthResponse
 *
 * WHY TWO STEPS:
 *   The pre-registration flow ensures all self-registered users have a
 *   verified email address before an account is created. No unverified
 *   accounts accumulate in the users table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final EmailSender emailSender;
    private final PasswordResetService passwordResetService;

    /**
     * Step 1 of registration: validate uniqueness, generate + send OTP.
     *
     * No user row is created here. The RegisterRequest is stored as JSON
     * in pending_registrations and retrieved on verify.
     *
     * RESEND PATH (OTP re-entry fix):
     *   If a pending registration already exists for this email, the user
     *   likely closed the OTP screen and came back. Instead of throwing
     *   EMAIL_TAKEN (which would deadlock them), we resend the OTP.
     *   Phone uniqueness is skipped on the resend path — it was already
     *   validated on the original request and is stored in payload_json.
     */
    @Transactional
    public OtpSentResponse initiateRegistration(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        String phone = request.getPhone().trim();

        // ── Check if a pending registration already exists for this email ──
        // This happens when the user closes the OTP screen and retries register.
        // Resend the OTP instead of throwing EMAIL_TAKEN.
        if (otpService.hasPendingRegistration(email)) {
            // Still check phone uniqueness — the user may have changed their phone,
            // or another user may have registered with that phone since the pending row was created.
            if (userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
                throw new BusinessException("PHONE_TAKEN", "An account with this phone number already exists");
            }
            otpService.checkResendCooldown(email);
            String newOtp = otpService.generateOtp();
            // Pass full request so payload_json is refreshed with the latest phone/fields
            otpService.updatePendingForResend(request, newOtp);
            emailSender.sendOtp(email, newOtp);
            log.info("OTP resent (re-register path): email={}", email);
            return OtpSentResponse.builder()
                    .email(email)
                    .message("A code was already sent. We've resent it to your email.")
                    .build();
        }

        // ── Check for duplicate email/phone in live users ──
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new BusinessException("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw new BusinessException("PHONE_TAKEN", "An account with this phone number already exists");
        }

        // ── Generate OTP and store pending registration ──
        String otp = otpService.generateOtp();
        otpService.storePending(request, otp);

        // ── Send OTP (console in dev, SMTP in prod) ──
        emailSender.sendOtp(email, otp);
        log.info("OTP sent for registration: email={}", email);

        return OtpSentResponse.builder()
                .email(email)
                .message("OTP sent to your email. Valid for 10 minutes.")
                .build();
    }

    /**
     * Step 2 of registration: validate OTP, create account, return JWT.
     *
     * OtpService.validateAndConsume() deletes the pending row on success.
     * The original RegisterRequest payload is returned for account creation.
     */
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        // ── Validate OTP — returns original RegisterRequest or throws ──
        RegisterRequest original = otpService.validateAndConsume(
                request.getEmail(), request.getOtp());

        String email = original.getEmail().toLowerCase().trim();
        String phone = original.getPhone().trim();

        // ── Re-check uniqueness (edge case: another user registered same
        //    email/phone during the OTP verification window) ──
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new BusinessException("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw new BusinessException("PHONE_TAKEN", "An account with this phone number already exists");
        }

        // ── Look up the role ──
        Role role = roleRepository.findByName(original.getRole().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Role", "name", original.getRole()));

        // ── Determine initial status ──
        // Both OWNER and RENTER start as PENDING_VERIFICATION.
        // Renters cannot book trucks until KYC is verified (BookingService guard),
        // so their status must reflect that pending state accurately.
        // Admin verifies KYC → sets kycVerified=true + status=ACTIVE for both roles.
        UserStatus initialStatus = UserStatus.PENDING_VERIFICATION;

        // ── Build and save user ──
        User user = User.builder()
                .role(role)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(original.getPassword()))
                .fullname(original.getFullname().trim())
                .dob(original.getDob() != null ? LocalDate.parse(original.getDob()) : null)
                .address(original.getAddress())
                .city(original.getCity())
                .state(original.getState())
                .country(original.getCountry() != null ? original.getCountry() : "India")
                .zipcode(original.getZipcode())
                .status(initialStatus)
                .kycVerified(false)
                .bankAccountName(original.getBankAccountName())
                .bankAccountNumber(original.getBankAccountNumber())
                .bankIfscCode(original.getBankIfscCode())
                .bankName(original.getBankName())
                .build();

        User savedUser = userRepository.save(user);
        log.info("User registered via OTP: id={}, email={}, role={}",
                savedUser.getId(), savedUser.getEmail(), role.getName());

        // ── Generate JWT and return ──
        String token = jwtService.generateAccessToken(
                savedUser.getId(), savedUser.getEmail(), role.getName());
        return buildAuthResponse(savedUser, token);
    }

    /**
     * Resend OTP — enforces 1-minute cooldown.
     *
     * Generates a fresh OTP, updates the pending row, and resends.
     * The cooldown window resets from each resend (not from the original request).
     */
    @Transactional
    public OtpSentResponse resendOtp(ResendOtpRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // ── Cooldown check (throws OTP_COOLDOWN if < 60 seconds since last send) ──
        otpService.checkResendCooldown(email);

        // ── Generate new OTP and update pending row (OTP + expiry only) ──
        String newOtp = otpService.generateOtp();
        otpService.updateOtpOnly(email, newOtp);

        // ── Send new OTP ──
        emailSender.sendOtp(email, newOtp);
        log.info("OTP resent: email={}", email);

        return OtpSentResponse.builder()
                .email(email)
                .message("New OTP sent to your email. Valid for 10 minutes.")
                .build();
    }

    /**
     * Login an existing user.
     *
     * FLOW:
     * 1. Find user by email → EMAIL_NOT_FOUND if not found
     * 2. Check password matches the hash → INCORRECT_PASSWORD if wrong
     * 3. Check account status — each non-ACTIVE status returns a specific error:
     *    SUSPENDED            → ACCOUNT_SUSPENDED
     *    REJECTED             → ACCOUNT_REJECTED
     *    PENDING_VERIFICATION → ACCOUNT_PENDING_VERIFICATION
     *    PENDING              → ACCOUNT_PENDING
     *    anything else        → ACCOUNT_INACTIVE
     * 4. Generate JWT token
     * 5. Return AuthResponse
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new BusinessException("EMAIL_NOT_FOUND",
                        "We couldn't find an account with that email address."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("INCORRECT_PASSWORD",
                    "Incorrect password. Please try again or reset your password.");
        }

        switch (user.getStatus()) {
            case ACTIVE, PENDING_VERIFICATION -> { /* proceed — PENDING_VERIFICATION users can log in but are
                                                      restricted by role-specific guards (e.g. booking requires
                                                      kycVerified=true, trucks require kycVerified=true) */ }
            case SUSPENDED -> throw new BusinessException("ACCOUNT_SUSPENDED",
                    "Your account has been suspended. Please contact support.");
            case REJECTED -> throw new BusinessException("ACCOUNT_REJECTED",
                    "Your account application was rejected. Please contact support for assistance.");
            case PENDING -> throw new BusinessException("ACCOUNT_PENDING",
                    "Please verify your email address before logging in.");
            default -> throw new BusinessException("ACCOUNT_INACTIVE",
                    "Your account is currently inactive. Please contact support.");
        }

        String token = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().getName());
        log.info("User logged in: id={}, email={}", user.getId(), user.getEmail());

        return buildAuthResponse(user, token);
    }

    /**
     * Forgot password — step 1: generate and send a password reset OTP.
     *
     * Rate-limited by a 1-minute cooldown (same as registration OTP).
     * Silently succeeds even when the email is not registered — this prevents
     * user enumeration (attackers cannot tell which emails are registered).
     *
     * Note: existing JWT sessions for the user remain valid after reset.
     * Server-side token invalidation (Redis blacklist) is planned for Phase 10.
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // ── Rate-limit all callers — apply cooldown before checking if user exists ──
        passwordResetService.checkResendCooldown(email);

        // ── Silent check: only send OTP if the user actually exists ──
        // We do NOT throw if the user is not found — that would reveal whether
        // the email is registered (user enumeration vulnerability).
        userRepository.findByEmail(email)
                .filter(u -> u.getDeletedAt() == null)
                .ifPresent(user -> {
                    String otp = otpService.generateOtp();
                    passwordResetService.storePendingReset(email, otp);
                    emailSender.sendPasswordResetOtp(email, otp);
                    log.info("Password reset OTP sent: email={}", email);
                });
    }

    /**
     * Forgot password — step 2: verify OTP and set new password.
     *
     * On success, the user's password is updated and the OTP token is consumed.
     * Note: existing JWT sessions remain valid (no blacklist — Phase 10 gap).
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // ── Validate and consume OTP — throws on bad/expired OTP ──
        passwordResetService.validateAndConsume(email, request.getOtp());

        // ── Find user (must exist and not be soft-deleted) ──
        User user = userRepository.findByEmail(email)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException("OTP_NOT_FOUND",
                        "No account found for this email."));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password reset successful: email={}", email);
    }

    // ── Private helpers ──

    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId().toString())
                        .build())
                .build();
    }
}
