package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One row per booking in the grouped invoice view — GET /admin/payments/by-booking.
 * Combines the CHARGE transaction with the optional MILEAGE_TOPUP transaction for the
 * same booking into a single summary row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBookingInvoiceResponse {

    private String bookingId;
    private String bookingNumber;
    private String renterName;

    // Invoice numbers
    private String chargeInvoiceNumber;     // from CHARGE txn
    private String mileageInvoiceNumber;    // from MILEAGE_TOPUP txn, null if no mileage

    // Dates / gateway
    private String paymentDate;             // CHARGE txn createdAt (ISO string)
    private String gateway;                 // "STRIPE" or "RAZORPAY"

    // Amounts
    private BigDecimal dayAmount;           // CHARGE txn amount
    private BigDecimal mileageAmount;       // MILEAGE_TOPUP txn amount, null if no mileage
    private BigDecimal totalAmount;         // dayAmount + mileageAmount (or dayAmount if no mileage)

    // Revenue split
    private BigDecimal ownerShare;          // sum of ownerAmount across both txns
    private BigDecimal platformShare;       // sum of platformFee across both txns

    // Derived statuses
    // FULLY_PAID   — CHARGE succeeded AND (no mileage OR mileage succeeded)
    // PARTIALLY_PAID — CHARGE succeeded AND mileage exists AND mileage not succeeded
    // PENDING      — CHARGE not succeeded
    private String paymentStatus;

    // SETTLED | PAYOUT_PENDING | PENDING
    private String settlementStatus;

    private boolean hasMileage;
}
