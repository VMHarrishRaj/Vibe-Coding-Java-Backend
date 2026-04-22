package com.truckhire.modules.auth.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.auth.dto.*;
import com.truckhire.modules.auth.service.AuthService;
import com.truckhire.modules.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

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
     * POST /api/v1/auth/forgot-password
     *
     * Step 1 of the forgot-password flow.
     * Sends a 6-digit OTP to the email address if a registered account exists.
     * Always returns 200 with a generic message — does NOT reveal whether the
     * email is registered (prevents user enumeration).
     *
     * Rate-limited: 1 request per minute per email (OTP_COOLDOWN on repeat).
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.success(
                "If this email is registered, you will receive a password reset code shortly.", null));
    }

    /**
     * POST /api/v1/auth/reset-password
     *
     * Step 2 of the forgot-password flow.
     * Validates the OTP and updates the user's password.
     *
     * Error codes:
     *   OTP_NOT_FOUND — no pending reset for this email (or OTP already used)
     *   OTP_EXPIRED   — OTP past 10-minute window
     *   OTP_INVALID   — wrong OTP code
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Password reset successful.", null));
    }

    /**
     * POST /api/v1/auth/logout
     *
     * Real server-side logout — blacklists the access token and revokes the refresh token.
     * Client must send the Authorization header (access token) + { refreshToken } in the body.
     * After this call, both tokens are dead on the server regardless of their expiry.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody LogoutRequest request,
            @AuthenticationPrincipal User user,
            HttpServletRequest httpRequest) {

        String bearerToken = httpRequest.getHeader("Authorization");
        String accessToken = (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer "))
                ? bearerToken.substring(7) : null;

        if (accessToken != null && user != null) {
            authService.logout(accessToken, request.getRefreshToken(), user.getId());
        }

        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
    }

    /**
     * POST /api/v1/auth/refresh
     *
     * Silently renew the access token using a valid refresh token.
     * No Authorization header needed — only the refresh token in the body.
     * Returns a new access token + rotated refresh token.
     *
     * The old refresh token is immediately revoked after this call (token rotation).
     * Frontend must always use the latest refresh token returned by this endpoint.
     *
     * Error codes:
     *   INVALID_REFRESH_TOKEN — expired, revoked, or invalid token
     *   ACCOUNT_SUSPENDED     — account suspended since last login
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {

        AuthResponse response = authService.refresh(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }
}
