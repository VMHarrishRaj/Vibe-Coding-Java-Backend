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
import org.springframework.security.authentication.BadCredentialsException;
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

    /**
     * Step 1 of registration: validate uniqueness, generate + send OTP.
     *
     * No user row is created here. The RegisterRequest is stored as JSON
     * in pending_registrations and retrieved on verify.
     */
    @Transactional
    public OtpSentResponse initiateRegistration(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        String phone = request.getPhone().trim();

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
        // Owners start as PENDING_VERIFICATION (need KYC)
        // Renters start as ACTIVE
        UserStatus initialStatus = Role.OWNER.equals(role.getName())
                ? UserStatus.PENDING_VERIFICATION
                : UserStatus.ACTIVE;

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

        // ── Generate new OTP and update pending row ──
        String newOtp = otpService.generateOtp();
        otpService.updatePendingForResend(email, newOtp);

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
     * 1. Find user by email → throw 401 if not found
     * 2. Check password matches the hash → throw 401 if wrong
     * 3. Check account is ACTIVE → throw 409 if suspended
     * 4. Generate JWT token
     * 5. Return AuthResponse
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessException("ACCOUNT_SUSPENDED", "Your account has been suspended. Contact support.");
        }

        String token = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().getName());
        log.info("User logged in: id={}, email={}", user.getId(), user.getEmail());

        return buildAuthResponse(user, token);
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
