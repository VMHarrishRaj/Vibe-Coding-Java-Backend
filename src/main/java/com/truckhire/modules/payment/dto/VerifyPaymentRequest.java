package com.truckhire.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for POST /payments/verify (Razorpay only).
 *
 * After Razorpay Checkout completes, the frontend receives these three values
 * from Razorpay and must POST them here for server-side HMAC verification.
 *
 * Stripe does NOT use this endpoint — Stripe uses webhooks as its
 * authoritative confirmation channel.
 */
@Getter
@Setter
@NoArgsConstructor
public class VerifyPaymentRequest {

    @NotBlank(message = "razorpayOrderId is required")
    private String razorpayOrderId;

    @NotBlank(message = "razorpayPaymentId is required")
    private String razorpayPaymentId;

    @NotBlank(message = "razorpaySignature is required")
    private String razorpaySignature;
}
