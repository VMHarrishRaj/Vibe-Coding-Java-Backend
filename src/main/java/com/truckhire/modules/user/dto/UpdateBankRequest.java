package com.truckhire.modules.user.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update Bank Request DTO — PUT /users/me/bank
 *
 * Dedicated DTO for bank detail updates.
 *
 * DESIGN DECISION (separate DTO):
 * We do not reuse UpdateProfileRequest here. A dedicated DTO means
 * the bank endpoint only accepts bank fields — no risk of a client
 * accidentally updating profile fields (name, city, etc.) through
 * the bank endpoint. Single responsibility, clear API contract.
 *
 * All fields are optional — partial update pattern applies.
 * Send only the fields you want to change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBankRequest {

    @Size(max = 255, message = "Bank account name must not exceed 255 characters")
    private String bankAccountName;

    @Size(max = 50, message = "Bank account number must not exceed 50 characters")
    private String bankAccountNumber;

    @Size(max = 50, message = "Bank routing number must not exceed 50 characters")
    private String bankRoutingNumber;

    @Size(max = 255, message = "Bank name must not exceed 255 characters")
    private String bankName;
}
