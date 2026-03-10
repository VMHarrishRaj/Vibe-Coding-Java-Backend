package com.truckhire.modules.auth.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.auth.dto.*;
import com.truckhire.modules.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth Controller — registration (OTP-gated), login, and logout.
 *
 * REGISTRATION IS NOW TWO STEPS:
 *
 *   POST /auth/register    → sends OTP to email (no account created yet)
 *   POST /auth/verify-otp  → validates OTP, creates account, returns JWT
 *   POST /auth/resend-otp  → resends OTP (1-min cooldown)
 *
 * All /auth/** routes are public (configured in SecurityConfig via /auth/**).
 * No JWT required for any endpoint in this controller.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/register
     *
     * Step 1 of the registration flow.
     * Validates email/phone uniqueness, generates OTP, stores pending registration,
     * and sends OTP to the user's email (logged to console in dev).
     *
     * Returns 200 OK with the email address — no account exists yet.
     * The client should show an OTP entry screen and call /auth/verify-otp next.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<OtpSentResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        OtpSentResponse response = authService.initiateRegistration(request);
        return ResponseEntity.ok(ApiResponse.success("OTP sent to your email", response));
    }

    /**
     * POST /api/v1/auth/verify-otp
     *
     * Step 2 of the registration flow.
     * Validates the OTP, creates the user account, and returns a JWT.
     *
     * Returns 201 Created — a new resource (user account) was created.
     *
     * Error codes:
     *   OTP_NOT_FOUND  — no pending registration for this email
     *   OTP_EXPIRED    — OTP past 10-minute window
     *   OTP_INVALID    — wrong OTP code
     *   EMAIL_TAKEN    — email registered by someone else during OTP window (race condition)
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request) {

        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", response));
    }

    /**
     * POST /api/v1/auth/resend-otp
     *
     * Resend OTP to the email address — enforces a 1-minute cooldown.
     *
     * Error codes:
     *   OTP_NOT_FOUND — no pending registration (user must call /register again)
     *   OTP_COOLDOWN  — less than 60 seconds since last OTP sent
     */
    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<OtpSentResponse>> resendOtp(
            @Valid @RequestBody ResendOtpRequest request) {

        OtpSentResponse response = authService.resendOtp(request);
        return ResponseEntity.ok(ApiResponse.success("New OTP sent to your email", response));
    }

    /**
     * POST /api/v1/auth/login
     *
     * Standard email + password login.
     * Returns JWT access token + user ID.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    /**
     * POST /api/v1/auth/logout
     *
     * Client-side logout stub — client must discard the JWT token.
     * Server-side token invalidation (Redis blacklist) is planned for Phase 10.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
    }
}
