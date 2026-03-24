package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for GET /payments/mine — unified payment history for RENTER and OWNER.
 *
 * RENTER: sees CHARGE + REFUND transactions (their outgoing payments)
 * OWNER:  sees PAYOUT transactions (their incoming earnings)
 *
 * The `type` field distinguishes CHARGE / REFUND / PAYOUT so the frontend
 * can render the appropriate badge/label without branching on role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyPaymentHistoryResponse {

    private String id;              // payment_transaction UUID
    private String bookingId;       // booking UUID
    private String bookingNumber;   // BK001, BK002...
    private String truckModel;      // e.g. "Tata 407" — null if truck deleted
    private BigDecimal amount;      // charged to renter (CHARGE/REFUND) or paid to owner (PAYOUT)
    private String status;          // SUCCEEDED | REFUNDED | PENDING | FAILED | PAID_OUT | PAYOUT_PENDING
    private String type;            // CHARGE | REFUND | PAYOUT
    private String gateway;         // STRIPE | RAZORPAY
    private String currency;        // USD | INR
    private String createdAt;       // ISO timestamp
}
