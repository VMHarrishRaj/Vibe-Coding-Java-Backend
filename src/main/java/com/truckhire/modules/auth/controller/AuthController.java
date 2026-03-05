package com.truckhire.modules.auth.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.auth.dto.AuthResponse;
import com.truckhire.modules.auth.dto.LoginRequest;
import com.truckhire.modules.auth.dto.RegisterRequest;
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
 * Auth Controller — handles registration and login HTTP endpoints.
 *
 * ENDPOINTS:
 * POST /api/v1/auth/register → Register a new user
 * POST /api/v1/auth/login → Login and get a JWT token
 *
 * These endpoints are PUBLIC (configured in SecurityConfig).
 * No JWT required to access them.
 *
 * FLOW:
 * HTTP Request → Controller → Service → Repository → Database
 * ↩ Response
 *
 * @RestController = @Controller + @ResponseBody
 *                 - @Controller: Spring registers this as an HTTP handler
 *                 - @ResponseBody: Return values are serialized to JSON
 *
 *                 @RequestMapping("/auth"): All endpoints start with /auth
 *                 Combined with context-path /api/v1 → /api/v1/auth
 *
 * @Valid: Triggers Jakarta Validation on the request DTO.
 *         If validation fails, Spring throws MethodArgumentNotValidException
 *         → caught by GlobalExceptionHandler → returns 400 with field errors.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/register
     *
     * Request body example:
     * {
     * "fullname": "Rahul Sharma",
     * "email": "rahul@example.com",
     * "phone": "+919876543210",
     * "password": "securePass123",
     * "role": "RENTER",
     * "city": "Mumbai",
     * "state": "Maharashtra",
     * "country": "India"
     * }
     *
     * Response: 201 Created with JWT token + user info
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED) // 201, not 200
                .body(ApiResponse.success("Registration successful", response));
    }

    /**
     * POST /api/v1/auth/login
     *
     * Request body:
     * {
     * "email": "rahul@example.com",
     * "password": "securePass123"
     * }
     *
     * Response: 200 OK with JWT token + user info
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);

        return ResponseEntity
                .ok(ApiResponse.success("Login successful", response));
    }
}
