package com.truckhire.modules.auth.service;

import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.auth.dto.AuthResponse;
import com.truckhire.modules.auth.dto.LoginRequest;
import com.truckhire.modules.auth.dto.RegisterRequest;
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
 * Auth Service — handles registration and login business logic.
 *
 * This is the SERVICE layer. It sits between Controller and Repository:
 *
 * Controller (HTTP) → Service (Business Logic) → Repository (Database)
 *
 * The service:
 * 1. Validates business rules (duplicate email, account status)
 * 2. Hashes passwords (never store plain text!)
 * 3. Creates entities and saves to DB
 * 4. Generates JWT tokens
 * 5. Builds response DTOs
 *
 * @RequiredArgsConstructor: Lombok generates a constructor with all final
 *                           fields.
 *                           Spring automatically injects (autowires) them.
 *
 * @Transactional: Wraps the method in a database transaction.
 *                 If any exception is thrown, ALL database changes are rolled
 *                 back.
 *                 This ensures data consistency (ACID).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Register a new user.
     *
     * FLOW:
     * 1. Check if email/phone already exists → throw if yes
     * 2. Look up the role by name (OWNER or RENTER)
     * 3. Hash the password
     * 4. Build User entity with all fields
     * 5. Save to database
     * 6. Generate JWT token
     * 7. Return AuthResponse with token + user info
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {

        // ── Step 1: Check for duplicate email/phone ──
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new BusinessException("PHONE_TAKEN", "An account with this phone number already exists");
        }

        // ── Step 2: Look up the role ──
        Role role = roleRepository.findByName(request.getRole().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Role", "name", request.getRole()));

        // ── Step 3: Determine initial status ──
        // Owners start as PENDING_VERIFICATION (need KYC)
        // Renters start as ACTIVE
        UserStatus initialStatus = Role.OWNER.equals(role.getName())
                ? UserStatus.PENDING_VERIFICATION
                : UserStatus.ACTIVE;

        // ── Step 4: Build the User entity ──
        User user = User.builder()
                .role(role)
                .email(request.getEmail().toLowerCase().trim())
                .phone(request.getPhone().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword())) // BCrypt hash
                .fullname(request.getFullname().trim())
                .dob(request.getDob() != null ? LocalDate.parse(request.getDob()) : null)
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .country(request.getCountry() != null ? request.getCountry() : "India")
                .zipcode(request.getZipcode())
                .status(initialStatus)
                .kycVerified(false)
                // Bank details (optional — owners can add during registration or later)
                .bankAccountName(request.getBankAccountName())
                .bankAccountNumber(request.getBankAccountNumber())
                .bankIfscCode(request.getBankIfscCode())
                .bankName(request.getBankName())
                .build();

        // ── Step 5: Save to database ──
        User savedUser = userRepository.save(user);
        log.info("User registered: id={}, email={}, role={}", savedUser.getId(), savedUser.getEmail(), role.getName());

        // ── Step 6: Generate JWT token ──
        String token = jwtService.generateAccessToken(savedUser.getId(), savedUser.getEmail(), role.getName());

        // ── Step 7: Build response ──
        return buildAuthResponse(savedUser, token);
    }

    /**
     * Login an existing user.
     *
     * FLOW:
     * 1. Find user by email → throw 401 if not found
     * 2. Check password matches the hash → throw 401 if wrong
     * 3. Check account is ACTIVE → throw 403 if suspended
     * 4. Generate JWT token
     * 5. Return AuthResponse
     */
    @Transactional(readOnly = true) // read-only = optimizer hint, no writes in this method
    public AuthResponse login(LoginRequest request) {

        // ── Step 1: Find user by email ──
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // ── Step 2: Verify password ──
        // passwordEncoder.matches(raw, hash) — bcrypt handles salt extraction
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        // ── Step 3: Check account status ──
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessException("ACCOUNT_SUSPENDED", "Your account has been suspended. Contact support.");
        }

        // ── Step 4: Generate JWT ──
        String token = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().getName());
        log.info("User logged in: id={}, email={}", user.getId(), user.getEmail());

        // ── Step 5: Return response ──
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
