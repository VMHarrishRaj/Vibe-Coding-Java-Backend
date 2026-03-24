package com.truckhire.modules.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Response for GET /bookings/{id}/payment.
 * Shows the current payment state for a booking.
 */
@Getter
@Builder
public class PaymentStatusResponse {

    private String bookingId;
    private String status;          // PaymentStatus enum name
    private String gateway;         // RAZORPAY or STRIPE
    private String type;            // CHARGE, REFUND, PAYOUT
    private java.math.BigDecimal amount;
    private String currency;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String createdAt;
    private String updatedAt;
}
