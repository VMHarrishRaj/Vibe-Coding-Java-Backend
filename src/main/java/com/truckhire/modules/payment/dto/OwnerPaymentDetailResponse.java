package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response for GET /payments/{id} — owner-facing payment detail.
 *
 * {id} is the PAYOUT transaction UUID from GET /payments/mine (OWNER).
 * Card details (paymentMethod, cardLast4, transactionId) come from the paired
 * CHARGE transaction — null if Stripe webhook has not yet populated them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerPaymentDetailResponse {

    // ── Header ──
    private String payoutTransactionId;  // the PAYOUT UUID passed in the request
    private String invoiceNumber;        // INV001 etc — also the "Receipt Number" in UI
    private String bookingNumber;        // BK001 etc
    private String invoiceDate;          // ISO timestamp of the CHARGE transaction
    private String status;              // raw: PAID_OUT | PAYOUT_PENDING
    private String displayStatus;       // "Completed" | "Pending"

    // ── Payment Information ──
    private String paymentMethod;       // card brand: "visa", "mastercard" etc — null if not yet captured
    private String cardLast4;           // "4532" — null if not yet captured
    private String transactionId;       // gateway payment ID from the CHARGE (pi_xxx / pay_xxx)
    private String gateway;             // STRIPE | RAZORPAY

    // ── Vehicle & Rental Details ──
    private String truckModel;          // make + model e.g. "RAM 2500"
    private String truckRegistration;   // registration number e.g. "TX-8901"
    private String renterName;          // renter full name
    private BigDecimal pricePerDay;     // daily rate snapshotted at booking time
    private Integer totalDays;
    private String startDate;           // YYYY-MM-DD
    private String endDate;             // YYYY-MM-DD
    private String pickupLocation;
    private String dropoffLocation;

    // ── Cost Breakdown ──
    private BigDecimal baseRental;          // dayAmount (totalDays x pricePerDay)
    private BigDecimal mileageAmount;       // null if no mileage charge
    private BigDecimal insuranceCost;       // null — future phase
    private BigDecimal additionalServicesCost; // null — future phase
    private BigDecimal tax;                 // null — future phase
    private BigDecimal totalAmount;         // gross total renter paid
    private BigDecimal ownerShare;          // net to owner ($)
    private BigDecimal ownerSharePercent;   // e.g. 90
    private BigDecimal portalCommission;    // platform fee ($)
    private BigDecimal platformFeePercent;  // e.g. 10
}
