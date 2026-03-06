package com.truckhire.modules.auth.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registration Request DTO.
 *
 * This is what the mobile app sends when a new user registers:
 * POST /api/v1/auth/register
 *
 * Jakarta Validation annotations (@NotBlank, @Email, etc.)
 * are checked AUTOMATICALLY by Spring when the controller
 * has @Valid on this parameter. If validation fails,
 * GlobalExceptionHandler returns a 400 with field errors.
 *
 * IMPORTANT: This is NOT the User entity.
 * DTOs protect the entity from direct exposure:
 * - Entity has password_hash → DTO has raw password
 * - Entity has role FK → DTO has role name string
 * - Entity has internal fields → DTO has only user-facing fields
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    private String fullname;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    private String password;

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "^(OWNER|RENTER)$", message = "Role must be either OWNER or RENTER")
    private String role;

    // ── Optional profile fields ──
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "DOB must be in YYYY-MM-DD format")
    private String dob;

    private String address;
    private String city;
    private String state;
    private String country;

    private String zipcode;

    // ── Optional bank details (for OWNER settlement payouts) ──
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankIfscCode;
    private String bankName;
}
