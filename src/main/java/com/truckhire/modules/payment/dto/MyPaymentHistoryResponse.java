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
 *
 * Owner-only fields (null for RENTER): truckId, renterName, startDate, endDate,
 * grossAmount, platformFeePercent, failureReason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyPaymentHistoryResponse {

    private String id;              // payment_transaction UUID
    private String bookingId;       // booking UUID
    private String bookingNumber;   // BK001, BK002...
    private String invoiceNumber;   // INV001, INV002... — from the paired CHARGE transaction (OWNER), or own invoice (RENTER)
    private String truckId;         // truck UUID — for mobile deep-link; null for RENTER
    private String truckModel;      // e.g. "Tata 407" — null if truck deleted
    private String renterName;      // renter full name (OWNER only)
    private String startDate;       // booking start date (OWNER only)
    private String endDate;         // booking end date (OWNER only)
    private BigDecimal grossAmount;       // what renter paid before fee deduction (OWNER only)
    private BigDecimal platformFeePercent; // platform fee % at time of payout (OWNER only)
    private BigDecimal platformFee;       // absolute $ fee deducted — grossAmount - amount (OWNER only)
    private BigDecimal amount;            // charged to renter (CHARGE/REFUND) or net paid to owner (PAYOUT)
    private String status;          // SUCCEEDED | REFUNDED | PENDING | FAILED | PAID_OUT | PAYOUT_PENDING
    private String failureReason;   // populated when status is PAYOUT_PENDING or FAILED (OWNER only)
    private String type;            // CHARGE | REFUND | PAYOUT
    private String gateway;         // STRIPE | RAZORPAY
    private String currency;        // USD | INR
    private String createdAt;       // ISO timestamp
}
