package com.truckhire.modules.payment.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.payment.dto.LinkBankAccountRequest;
import com.truckhire.modules.payment.dto.PaymentInitiatedResponse;
import com.truckhire.modules.payment.dto.PaymentStatusResponse;
import com.truckhire.modules.payment.dto.VerifyPaymentRequest;
import com.truckhire.modules.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * PaymentController — payment initiation, verification, and status.
 *
 * POST /bookings/{id}/pay     — RENTER initiates payment
 * POST /payments/verify       — RENTER submits Razorpay verification (Razorpay only)
 * GET  /bookings/{id}/payment — RENTER/OWNER checks payment status
 * GET  /config/payment        — Public endpoint for frontend SDK config
 */
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final com.truckhire.modules.payment.service.PlatformSettingsService platformSettingsService;

    /**
     * Initiate payment for a booking.
     * Returns the gateway-specific payload the frontend needs to open the payment SDK.
     */
    @PostMapping("/bookings/{id}/pay")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<PaymentInitiatedResponse>> initiatePayment(
            @PathVariable UUID id) {
        UUID renterId = SecurityUtils.getCurrentUser().getId();
        PaymentInitiatedResponse response = paymentService.initiatePayment(id, renterId);
        return ResponseEntity.ok(ApiResponse.success("Payment order created", response));
    }

    /**
     * Verify Razorpay payment signature (Razorpay flow only).
     * Frontend calls this after Razorpay Checkout returns payment_id + signature.
     */
    @PostMapping("/payments/verify")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<Void>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request) {
        paymentService.verifyRazorpayPayment(
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );
        return ResponseEntity.ok(ApiResponse.success("Payment verified. Booking confirmed.", null));
    }

    /**
     * Get payment status for a booking.
     * Accessible by the renter or owner of the booking.
     */
    @GetMapping("/bookings/{id}/payment")
    @PreAuthorize("hasAnyRole('RENTER', 'OWNER', 'ADMIN')")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPaymentStatus(
            @PathVariable UUID id) {
        PaymentStatusResponse response = paymentService.getPaymentStatus(id);
        return ResponseEntity.ok(ApiResponse.success("Payment status retrieved", response));
    }

    /**
     * Public endpoint: returns active gateway, currency, and public key.
     * Frontend uses this to initialize the correct payment SDK before the user
     * even reaches the payment screen.
     */
    @GetMapping("/config/payment")
    public ResponseEntity<ApiResponse<com.truckhire.modules.payment.dto.PlatformSettingsResponse>> getPaymentConfig() {
        var config = platformSettingsService.getSettingsResponse();
        return ResponseEntity.ok(ApiResponse.success("Payment config retrieved", config));
    }

    /**
     * Verify mileage top-up payment (Razorpay only).
     * Called after renter completes the second Razorpay Checkout for mileage charges.
     * On success, triggers owner payout.
     */
    @PostMapping("/payments/verify-mileage")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<Void>> verifyMileagePayment(
            @Valid @RequestBody VerifyPaymentRequest request) {
        paymentService.verifyMileagePayment(
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature()
        );
        return ResponseEntity.ok(ApiResponse.success("Mileage payment verified. Payout initiated.", null));
    }

    /**
     * Owner links their bank account to Razorpay.
     * Creates a Razorpay Contact (if not already linked) + Fund Account.
     * Required before any payout can be made to the owner.
     */
    @PostMapping("/owners/me/razorpay/link-account")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> linkRazorpayAccount(
            @Valid @RequestBody LinkBankAccountRequest request) {
        UUID ownerId = SecurityUtils.getCurrentUser().getId();
        paymentService.linkOwnerBankAccount(ownerId, request);
        return ResponseEntity.ok(ApiResponse.success("Bank account linked to Razorpay successfully", null));
    }
}
