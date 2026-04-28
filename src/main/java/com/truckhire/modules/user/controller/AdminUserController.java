package com.truckhire.modules.user.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.auth.dto.AuthResponse;
import com.truckhire.modules.booking.service.BookingService;
import com.truckhire.modules.payment.dto.AdminPaymentListResponse;
import com.truckhire.modules.payment.service.PaymentService;
import com.truckhire.modules.user.dto.AdminCreateUserRequest;
import com.truckhire.modules.user.dto.AdminCreateUserResponse;
import com.truckhire.modules.user.dto.AdminUserListResponse;
import com.truckhire.modules.user.dto.CreateAdminRequest;
import com.truckhire.modules.user.dto.KycDocumentResponse;
import com.truckhire.modules.user.dto.RenterBookingSummaryResponse;
import com.truckhire.modules.user.dto.UserProfileResponse;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.service.KycService;
import com.truckhire.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin User Controller — admin-only user management endpoints.
 *
 * ENDPOINTS:
 * GET /admin/users → List all users (paginated, filterable)
 * GET /admin/users/{id} → User detail
 * PUT /admin/users/{id}/activate → Activate a user
 * PUT /admin/users/{id}/suspend → Suspend a user
 * POST /admin/users/create-admin → Create a new admin
 * GET /admin/users/{id}/kyc → View user's KYC documents
 * PUT /admin/users/{id}/verify-kyc → Verify KYC (set kyc_verified = true)
 *
 * AUTH: All endpoints require ADMIN role.
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final KycService kycService;
    private final BookingService bookingService;
    private final PaymentService paymentService;

    /**
     * GET
     * /api/v1/admin/users?role=OWNER&status=ACTIVE&page=0&size=20&sort=createdAt,desc
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<AdminUserListResponse>>> getAllUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(name = "search", required = false) String q,
            @RequestParam(required = false) String stripeConnected,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        PagedResponse<AdminUserListResponse> users = userService.getAllUsers(role, status, q, stripeConnected, pageable);
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", users));
    }

    /**
     * GET /api/v1/admin/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getUserById(@PathVariable UUID id) {
        UserProfileResponse user = userService.getAdminUserDetail(id);
        return ResponseEntity.ok(ApiResponse.success("User retrieved", user));
    }

    /**
     * DELETE /api/v1/admin/users/{id}
     *
     * Soft-deletes a user. Sets deleted_at — user is invisible to all queries.
     * Admin cannot delete themselves.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable UUID id) {
        User admin = SecurityUtils.getCurrentUser();
        if (admin.getId().equals(id)) {
            throw new com.truckhire.common.exception.BusinessException(
                    "CANNOT_DELETE_SELF", "You cannot delete your own account");
        }
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deleted", null));
    }

    /**
     * PUT /api/v1/admin/users/{id}/activate
     */
    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<Void>> activateUser(@PathVariable UUID id) {
        userService.activateUser(id);
        return ResponseEntity.ok(ApiResponse.success("User activated", null));
    }

    /**
     * PUT /api/v1/admin/users/{id}/suspend
     */
    @PutMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspendUser(@PathVariable UUID id) {
        User admin = SecurityUtils.getCurrentUser();
        userService.suspendUser(id, admin.getId());
        return ResponseEntity.ok(ApiResponse.success("User suspended", null));
    }

    /**
     * POST /api/v1/admin/users
     *
     * Admin registers a new OWNER or RENTER. The user is created immediately as
     * ACTIVE. A welcome email with login credentials is sent to the user's inbox.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AdminCreateUserResponse>> createUser(
            @Valid @RequestBody AdminCreateUserRequest request) {

        AdminCreateUserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered successfully", response));
    }

    /**
     * POST /api/v1/admin/users/create-admin
     */
    @PostMapping("/create-admin")
    public ResponseEntity<ApiResponse<AuthResponse>> createAdmin(
            @Valid @RequestBody CreateAdminRequest request) {

        AuthResponse response = userService.createAdmin(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Admin created", response));
    }

    // ═══════════════════════════════════════
    // KYC MANAGEMENT ENDPOINTS
    // ═══════════════════════════════════════

    /**
     * GET /api/v1/admin/users/{id}/kyc
     *
     * View a user's uploaded KYC documents. Used by admin to review
     * documents before verifying KYC.
     */
    @GetMapping("/{id}/kyc")
    public ResponseEntity<ApiResponse<List<KycDocumentResponse>>> getUserKycDocuments(
            @PathVariable UUID id) {
        List<KycDocumentResponse> documents = kycService.getKycDocumentsByUserId(id);
        return ResponseEntity.ok(ApiResponse.success("KYC documents retrieved", documents));
    }

    /**
     * PUT /api/v1/admin/users/{id}/verify-kyc
     *
     * Verify an owner's KYC. This sets user.kyc_verified = true and marks
     * all PENDING documents as VERIFIED. Also activates the user if they
     * were in PENDING_VERIFICATION status.
     *
     * This is the gate that allows owners to add trucks.
     */
    @PutMapping("/{id}/verify-kyc")
    public ResponseEntity<ApiResponse<Void>> verifyKyc(@PathVariable UUID id) {
        User admin = SecurityUtils.getCurrentUser();
        kycService.verifyKyc(id, admin.getId());
        return ResponseEntity.ok(ApiResponse.success("KYC verified", null));
    }

    /**
     * PUT /api/v1/admin/users/{id}/reject-kyc?reason=...
     *
     * Reject an owner's KYC. Marks PENDING documents as REJECTED,
     * clears kyc_verified, and sets owner's APPROVED trucks → INACTIVE.
     */
    @PutMapping("/{id}/reject-kyc")
    public ResponseEntity<ApiResponse<Void>> rejectKyc(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "KYC documents do not meet requirements") String reason) {
        User admin = SecurityUtils.getCurrentUser();
        kycService.rejectKyc(id, admin.getId(), reason);
        return ResponseEntity.ok(ApiResponse.success("KYC rejected", null));
    }

    // ═══════════════════════════════════════
    // RENTER DETAIL — BOOKINGS & PAYMENTS
    // ═══════════════════════════════════════

    /**
     * GET /api/v1/admin/users/{id}/bookings
     *
     * Returns booking statistics + paginated booking list for a specific renter.
     * Used by the admin Client/Renter detail page.
     *
     * Response:
     *   stats.totalBookings    — all-time booking count
     *   stats.totalSpent       — sum of totalAmount for COMPLETED bookings
     *   stats.avgBookingValue  — totalSpent / completed count (null if no completed bookings)
     *   stats.lastBookingDate  — createdAt of the most recent booking
     *   bookings               — paginated BookingListResponse list
     */
    @GetMapping("/{id}/bookings")
    public ResponseEntity<ApiResponse<RenterBookingSummaryResponse>> getRenterBookings(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        RenterBookingSummaryResponse response = bookingService.getAdminRenterBookings(id, pageable);
        return ResponseEntity.ok(ApiResponse.success("Renter bookings retrieved", response));
    }

    /**
     * GET /api/v1/admin/users/{id}/payments
     *
     * Returns paginated invoice list (CHARGE + MILEAGE_TOPUP transactions) for a specific renter.
     * Used by the admin Client/Renter detail page — Invoice Details table.
     *
     * Each row: id (use as chargeTransactionId for download), invoiceNumber, bookingNumber,
     *           total, paymentDate, paymentStatus, displayPaymentStatus, settlementStatus
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<ApiResponse<PagedResponse<AdminPaymentListResponse>>> getRenterPayments(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        PagedResponse<AdminPaymentListResponse> response = paymentService.getAdminRenterPayments(id, pageable);
        return ResponseEntity.ok(ApiResponse.success("Renter payments retrieved", response));
    }
}
