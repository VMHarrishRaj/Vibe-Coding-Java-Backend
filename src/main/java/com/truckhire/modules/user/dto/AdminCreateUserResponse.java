package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for POST /admin/users — admin registers a new OWNER or RENTER.
 *
 * temporaryPassword is shown once in the response for the admin to share with
 * the user out-of-band.
 * NOTE (future — email notification): Replace with SMTP delivery and remove
 * temporaryPassword from the response body once email is confirmed working.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCreateUserResponse {
    private String userId;
    private String email;
    private String role;
    private String status;
    private String temporaryPassword;
}
