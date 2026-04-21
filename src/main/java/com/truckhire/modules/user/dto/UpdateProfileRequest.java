package com.truckhire.modules.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update Profile Request DTO — PUT /users/me
 *
 * DESIGN DECISION (Partial Update Pattern):
 * Only non-null fields in this request are applied to the user record.
 * This means the client can send ONLY the fields they want to change:
 * { "city": "Bangalore" } ← changes only city, everything else untouched
 *
 * WHAT USERS CANNOT CHANGE THEMSELVES:
 * - email → requires email verification flow (future phase)
 * - phone → requires OTP verification flow (future phase)
 * - role → admin-only operation
 * - status → admin-only operation
 * - kycVerified → admin-only verification
 * - password → requires separate change-password endpoint (future)
 *
 * All fields are optional (nullable). Validation only runs on non-null values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    private String fullname;

    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    private String middleName;

    @Size(max = 20, message = "Alternate phone must not exceed 20 characters")
    private String alternatePhone;

    @Size(max = 255, message = "Company name must not exceed 255 characters")
    private String companyName;

    @Size(max = 50, message = "Tax ID must not exceed 50 characters")
    private String taxId;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "DOB must be in YYYY-MM-DD format")
    private String dob;

    @Size(max = 500, message = "Address must not exceed 500 characters")
    private String address;

    @Size(max = 100, message = "City must not exceed 100 characters")
    private String city;

    @Size(max = 100, message = "State must not exceed 100 characters")
    private String state;

    @Size(max = 100, message = "Country must not exceed 100 characters")
    private String country;

    private String zipcode;

    // ── Bank details (for OWNER settlement payouts) ──
    @Size(max = 255, message = "Bank account name must not exceed 255 characters")
    private String bankAccountName;

    @Size(max = 50, message = "Bank account number must not exceed 50 characters")
    private String bankAccountNumber;

    @Size(max = 50, message = "Bank routing number must not exceed 50 characters")
    private String bankRoutingNumber;

    @Size(max = 255, message = "Bank name must not exceed 255 characters")
    private String bankName;
}
