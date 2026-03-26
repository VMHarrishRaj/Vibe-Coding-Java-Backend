package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * User Profile Response DTO — full profile returned by GET /users/me.
 *
 * DESIGN DECISION (Phase 3 — complements Phase 2 modular approach):
 * Phase 2 auth response returns only user ID.
 * After login, the client calls GET /users/me to get the full profile.
 * This separation means:
 * - Auth is fast (minimal payload)
 * - Profile data is fetched only when needed
 * - Profile endpoint can be extended without touching auth
 *
 * WHAT'S EXCLUDED (security):
 * - passwordHash → never exposed
 * - deletedAt → internal audit field
 * - updatedAt → internal field (createdAt is useful for "member since")
 *
 * WHAT'S INCLUDED:
 * Everything the mobile app needs to render the user profile screen
 * and make routing decisions (role, status, kycVerified).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private String id;
    private String fullname;
    private String email;
    private String phone;
    private String role;
    private String status;

    // ── Profile fields ──
    private String dob;
    private String address;
    private String city;
    private String state;
    private String country;
    private String zipcode;

    // ── Verification ──
    private boolean kycVerified;
    private String profileImageUrl;

    // ── Bank Details (for OWNER settlement payouts) ──
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankRoutingNumber;
    private String bankName;

    // ── Metadata ──
    private String createdAt; // ISO timestamp — "member since"

    // ── Gateway connection status (OWNER only — false for ADMIN/RENTER) ──
    private boolean stripeConnected;  // true when stripeAccountId is set on the user

    // ── OWNER role only — null for ADMIN/RENTER ──
    private List<OwnedVehicleSummary> vehiclesOwned;
}
