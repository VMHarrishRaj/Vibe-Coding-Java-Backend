package com.truckhire.modules.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Returned after POST /bookings/{id}/pay.
 *
 * Frontend uses these fields to initialise the payment SDK:
 * - Razorpay: open Checkout with keyId + orderId + amount + currency
 * - Stripe: call stripe.confirmPayment() with clientSecret + publishableKey
 *
 * The frontend should check the 'gateway' field first to know which SDK to use.
 */
@Getter
@Builder
public class PaymentInitiatedResponse {

    private String gateway;          // RAZORPAY or STRIPE

    // Razorpay fields (null for Stripe)
    private String orderId;          // Razorpay order_id (rzp_order_...)
    private String keyId;            // Razorpay publishable key (rzp_test_...)

    // Stripe fields (null for Razorpay)
    private String clientSecret;     // Stripe PaymentIntent client_secret
    private String publishableKey;   // Stripe publishable key (pk_test_...)

    private String bookingId;
    private Long amount;             // in smallest currency unit (paise/cents) — for SDK
    private String currency;
}
