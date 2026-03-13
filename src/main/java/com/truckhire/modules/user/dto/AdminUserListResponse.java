package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin User List Response DTO — condensed user info for paginated admin views.
 *
 * DESIGN DECISION:
 * Admin list endpoints return a SUBSET of user fields to keep payloads small.
 * Full detail is available via GET /admin/users/{id} which returns
 * UserProfileResponse.
 *
 * This DTO contains just enough for the admin to scan the list and decide
 * which users to drill into: name, contact, role, status, verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserListResponse {

    private String id;
    private String fullname;
    private String email;
    private String phone;
    private String role;
    private String status;
    private boolean kycVerified;
    private String createdAt;
    private long vehicleCount;
}
