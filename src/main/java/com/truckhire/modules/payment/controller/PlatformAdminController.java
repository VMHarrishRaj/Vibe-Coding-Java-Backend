package com.truckhire.modules.payment.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.payment.dto.PlatformSettingsRequest;
import com.truckhire.modules.payment.dto.PlatformSettingsResponse;
import com.truckhire.modules.payment.service.PaymentService;
import com.truckhire.modules.payment.service.PlatformSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * PlatformAdminController — admin endpoints for platform configuration and payout management.
 *
 * GET  /admin/platform/settings        — view current gateway/currency/fee settings
 * PUT  /admin/platform/settings        — switch gateway (RAZORPAY ↔ STRIPE), currency, fee
 * POST /admin/bookings/{id}/payout     — manually trigger owner payout for a COMPLETED booking
 *
 * Payout is admin-initiated (not automatic) so the admin can review and confirm
 * before funds are released to the owner.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final PlatformSettingsService platformSettingsService;
    private final PaymentService paymentService;

    @GetMapping("/admin/platform/settings")
    public ResponseEntity<ApiResponse<PlatformSettingsResponse>> getSettings() {
        PlatformSettingsResponse response = platformSettingsService.getSettingsResponse();
        return ResponseEntity.ok(ApiResponse.success("Platform settings retrieved", response));
    }

    @PutMapping("/admin/platform/settings")
    public ResponseEntity<ApiResponse<PlatformSettingsResponse>> updateSettings(
            @Valid @RequestBody PlatformSettingsRequest request) {
        var adminId = SecurityUtils.getCurrentUser().getId();
        PlatformSettingsResponse response = platformSettingsService.updateSettings(adminId, request);
        return ResponseEntity.ok(ApiResponse.success("Platform settings updated", response));
    }

    /**
     * Admin manually initiates owner payout for a COMPLETED booking.
     *
     * Guards (enforced inside PaymentService.initiateOwnerPayout):
     * - Booking must be COMPLETED (a SUCCEEDED CHARGE must exist)
     * - If a PAYOUT_PENDING or PAID_OUT transaction already exists, payout is skipped with a warning log
     *
     * Why admin-initiated?
     * Payout requires manual review — admin confirms the rental is truly complete
     * and dispute-free before releasing funds to the owner.
     */
    @PostMapping("/admin/bookings/{id}/payout")
    public ResponseEntity<ApiResponse<Void>> initiateOwnerPayout(@PathVariable("id") UUID bookingId) {
        paymentService.initiateOwnerPayout(bookingId);
        return ResponseEntity.ok(ApiResponse.success("Owner payout initiated", null));
    }
}
