package com.truckhire.modules.user.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * User Entity — maps to the 'users' table.
 *
 * Represents any user in the system: ADMIN, OWNER, or RENTER.
 * The role determines what they can do (enforced by Spring Security).
 *
 * KEY DESIGN DECISIONS:
 * 1. UUID as PK — prevents enumeration attacks (can't guess user IDs)
 * 2. role_id FK to roles table — normalized (not an enum column)
 * 3. Extends BaseAuditEntity — gets created_at, updated_at, deleted_at
 * 4. Soft delete via deleted_at — users are never physically deleted
 *
 * LIFECYCLE:
 * Registration → status = ACTIVE (or PENDING_VERIFICATION for OWNER)
 * Admin suspend → status = SUSPENDED
 * Soft delete → deleted_at = now()
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The user's role. Eagerly fetched because we check it on every request.
     * FetchType.EAGER = load role WITH the user (1 query with JOIN)
     * FetchType.LAZY = load role only when accessed (2 queries)
     * Since we check role on every authenticated request, EAGER is better.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 255)
    private String fullname;

    @Column
    private LocalDate dob;

    @Column(length = 500)
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 100)
    private String country;

    @Column(length = 10)
    private String zipcode;

    /**
     * User account status.
     * Stored as string in DB (not ordinal) for readability.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "kyc_verified", nullable = false)
    @Builder.Default
    private boolean kycVerified = false;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    // ── Bank Details (for owner settlement payouts) ──

    @Column(name = "bank_account_name", length = 255)
    private String bankAccountName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_ifsc_code", length = 20)
    private String bankIfscCode;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    // ── Payment Gateway IDs (for owner payouts) ──

    @Column(name = "razorpay_contact_id", length = 255)
    private String razorpayContactId;

    @Column(name = "razorpay_fund_account_id", length = 255)
    private String razorpayFundAccountId;

    @Column(name = "stripe_account_id", length = 255)
    private String stripeAccountId;
}
