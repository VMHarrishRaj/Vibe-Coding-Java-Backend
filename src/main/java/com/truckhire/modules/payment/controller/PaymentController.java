package com.truckhire.modules.payment.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.payment.dto.DownloadTokenResponse;
import com.truckhire.modules.payment.dto.LinkBankAccountRequest;
import com.truckhire.modules.payment.dto.MyPaymentHistoryResponse;
import com.truckhire.modules.payment.dto.OwnerPaymentDetailResponse;
import com.truckhire.modules.payment.dto.RenterPaymentPageResponse;
import com.truckhire.modules.payment.dto.StripeConnectResponse;
import com.truckhire.modules.payment.dto.PaymentInitiatedResponse;
import com.truckhire.modules.payment.dto.PaymentStatusResponse;
import com.truckhire.modules.payment.dto.VerifyPaymentRequest;
import com.truckhire.modules.payment.service.DownloadTokenService;
import com.truckhire.modules.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final DownloadTokenService downloadTokenService;

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
        return ResponseEntity.ok(ApiResponse.success("Mileage payment verified. Admin will initiate owner payout.", null));
    }

    /**
     * Payment history for the current user.
     * RENTER: booking-grouped response (RenterPaymentPageResponse) — fixes status label bug,
     *         includes totals summary and invoices array per booking.
     * OWNER:  flat PAYOUT list (PagedResponse<MyPaymentHistoryResponse>) — unchanged.
     * Role is resolved from the JWT automatically.
     */
    @GetMapping("/payments/mine")
    @PreAuthorize("hasAnyRole('RENTER', 'OWNER')")
    public ResponseEntity<ApiResponse<?>> getMyPayments(
            @PageableDefault(size = 50) Pageable pageable) {
        var currentUser = SecurityUtils.getCurrentUser();
        String role = currentUser.getRole().getName();
        if ("RENTER".equals(role)) {
            RenterPaymentPageResponse response = paymentService.getRenterPaymentHistory(currentUser, pageable);
            return ResponseEntity.ok(ApiResponse.success("Payment history retrieved", response));
        } else {
            PagedResponse<MyPaymentHistoryResponse> response = paymentService.getMyPayments(currentUser, pageable);
            return ResponseEntity.ok(ApiResponse.success("Payment history retrieved", response));
        }
    }

    /**
     * GET /payments/{id}
     * Owner-facing payment detail — full breakdown for a single payout transaction.
     * {id} is the PAYOUT transaction UUID from GET /payments/mine (OWNER role).
     * Scoped: only the owner of the booking may access their own payout details.
     */
    @GetMapping("/payments/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<OwnerPaymentDetailResponse>> getOwnerPaymentDetail(
            @PathVariable UUID id) {
        UUID ownerId = SecurityUtils.getCurrentUser().getId();
        OwnerPaymentDetailResponse response = paymentService.getOwnerPaymentDetail(id, ownerId);
        return ResponseEntity.ok(ApiResponse.success("Payment detail retrieved", response));
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

    /**
     * POST /owners/me/stripe/connect
     *
     * Step 1 of Stripe Connect onboarding. Creates a Stripe Connected Account
     * (or reuses an existing one) and returns a hosted onboarding URL.
     *
     * Mobile opens the URL in the native browser. After the owner completes
     * (or abandons) onboarding, Stripe redirects to GET /stripe/connect/return.
     *
     * Idempotent: safe to call multiple times — reuses the existing account and
     * generates a fresh link each time.
     */
    @PostMapping("/owners/me/stripe/connect")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<StripeConnectResponse>> initiateStripeConnect() {
        UUID ownerId = SecurityUtils.getCurrentUser().getId();
        StripeConnectResponse response = paymentService.initiateStripeConnect(ownerId);
        return ResponseEntity.ok(ApiResponse.success("Stripe onboarding URL generated", response));
    }

    /**
     * GET /stripe/connect/return
     *
     * Stripe redirects here after owner completes (or abandons) onboarding.
     * PUBLIC endpoint — no JWT (Stripe drives this redirect, not the owner's app).
     *
     * Checks if onboarding is complete (chargesEnabled=true on the account).
     * Returns a plain JSON response the mobile deep link handler can read,
     * OR redirects to truckhire://stripe-connect/return for native app handling.
     *
     * ownerId param is set by us when building the returnUrl in PaymentService.
     */
    @GetMapping("/stripe/connect/return")
    public ResponseEntity<ApiResponse<Void>> stripeConnectReturn(
            @RequestParam UUID ownerId) {
        boolean complete = paymentService.completeStripeConnect(ownerId);
        String message = complete
                ? "Stripe Connect onboarding complete. Payouts are now enabled."
                : "Stripe Connect onboarding not yet complete. Please finish the setup in the Stripe dashboard.";
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    /**
     * GET /stripe/connect/refresh
     *
     * Stripe redirects here when an Account Link expires before the owner completes onboarding.
     * We generate a fresh onboarding link and redirect the owner back to Stripe.
     * PUBLIC endpoint.
     */
    @GetMapping("/stripe/connect/refresh")
    public ResponseEntity<ApiResponse<StripeConnectResponse>> stripeConnectRefresh(
            @RequestParam UUID ownerId,
            @RequestParam String accountId) {
        StripeConnectResponse response = paymentService.initiateStripeConnect(ownerId);
        return ResponseEntity.ok(ApiResponse.success("New Stripe onboarding link generated", response));
    }

    /**
     * GET /payments/{id}/download
     * Download invoice PDF for a specific CHARGE transaction.
     * RENTER: can download invoices for their own bookings.
     * OWNER: can download invoices for bookings on their trucks.
     */
    @GetMapping("/payments/{id}/download")
    @PreAuthorize("hasAnyRole('RENTER', 'OWNER')")
    public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable UUID id) {
        UUID userId = SecurityUtils.getCurrentUser().getId();
        byte[] pdf = paymentService.generateInvoicePdfForUser(id, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "invoice-" + id + ".pdf");
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    /**
     * POST /payments/{id}/download-token
     * Issues a short-lived signed URL for unauthenticated PDF download.
     * Used by mobile: frontend opens the returned url via Linking.openURL (device browser),
     * which cannot send Authorization headers.
     * Token is stored in Redis with a 5-minute TTL, reusable within that window.
     */
    @PostMapping("/payments/{id}/download-token")
    @PreAuthorize("hasAnyRole('RENTER', 'OWNER')")
    public ResponseEntity<ApiResponse<DownloadTokenResponse>> issueDownloadToken(
            @PathVariable UUID id,
            HttpServletRequest request) {
        UUID userId = SecurityUtils.getCurrentUser().getId();
        // Access-check: reuse the existing guard — throws if user has no access to this invoice
        paymentService.generateInvoicePdfForUser(id, userId);
        String token = downloadTokenService.issueToken(id, userId);
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "")
                + request.getContextPath();
        String url = baseUrl + "/api/v1/payments/download?token=" + token;
        DownloadTokenResponse response = DownloadTokenResponse.builder()
                .url(url)
                .expiresIn(DownloadTokenService.TTL_SECONDS)
                .build();
        return ResponseEntity.ok(ApiResponse.success("Download token issued", response));
    }

    /**
     * GET /payments/download?token={token}
     * Public endpoint — no JWT required. Validates the short-lived Redis token
     * issued by POST /payments/{id}/download-token and streams the invoice PDF.
     */
    @GetMapping("/payments/download")
    public ResponseEntity<byte[]> downloadInvoiceByToken(@RequestParam String token) {
        String[] parts = downloadTokenService.resolveToken(token);
        if (parts == null) {
            throw new com.truckhire.common.exception.BusinessException(
                    "INVALID_DOWNLOAD_TOKEN", "Download link is invalid or has expired.");
        }
        UUID transactionId = UUID.fromString(parts[0]);
        UUID userId = UUID.fromString(parts[1]);
        byte[] pdf = paymentService.generateInvoicePdfForUser(transactionId, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "invoice-" + transactionId + ".pdf");
        return ResponseEntity.ok().headers(headers).body(pdf);
    }
}
