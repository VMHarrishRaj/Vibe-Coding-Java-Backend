package com.truckhire.modules.user.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create Admin Request DTO — POST /admin/users/create-admin
 *
 * DESIGN DECISION (Closed Admin Registration):
 * Public registration (POST /auth/register) is restricted to OWNER and RENTER
 * via the regex pattern ^(OWNER|RENTER)$.
 *
 * Admin accounts can ONLY be created by existing admins through this
 * dedicated endpoint. This is a security best practice:
 * - No self-registration as admin
 * - Audit trail: the creating admin is logged
 * - Separate validation rules (no role field needed — always ADMIN)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAdminRequest {

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
}
