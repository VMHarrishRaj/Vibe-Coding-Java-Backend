package com.truckhire.modules.user.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for POST /admin/users — admin registers a new OWNER or RENTER.
 *
 * Role is restricted to OWNER or RENTER. ADMIN creation uses the separate
 * POST /admin/users/create-admin endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCreateUserRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    private String fullname;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits")
    private String phone;

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "^(OWNER|RENTER)$", message = "Role must be OWNER or RENTER")
    private String role;
}
