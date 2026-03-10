package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for POST /admin/users — admin registers a new OWNER or RENTER.
 * A welcome email with login credentials is sent directly to the user's inbox.
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
}
