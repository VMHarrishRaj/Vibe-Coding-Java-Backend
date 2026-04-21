package com.truckhire.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login Request DTO.
 *
 * POST /api/v1/auth/login
 * { "email": "user@example.com", "password": "secret123" }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Source is required (MOBILE or WEBAPP)")
    @Pattern(regexp = "^(MOBILE|WEBAPP)$", message = "Source must be MOBILE or WEBAPP")
    private String source;
}
