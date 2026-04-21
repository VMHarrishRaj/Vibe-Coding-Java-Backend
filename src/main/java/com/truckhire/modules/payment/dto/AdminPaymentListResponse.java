package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for GET /admin/payments — paginated invoice list.
 * One row per CHARGE or MILEAGE_TOPUP transaction.
 * A booking with mileage charges will appear as two rows in this list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaymentListResponse {

    private String id;               // payment_transaction UUID — use for GET /admin/payments/{id}
    private String invoiceNumber;    // INV001, INV002...
    private String bookingNumber;    // BK001, BK002...
    private String renterName;
    private String paymentDate;      // transaction createdAt
    private String invoiceType;      // "DAY_RATE" or "MILEAGE" — derived from transaction type
    private BigDecimal total;        // amount charged to renter
    private BigDecimal ownerShare;   // owner_amount (null until payout initiated)
    private BigDecimal platformShare;// platform_fee (null until payout initiated)
    private String paymentStatus;           // raw: SUCCEEDED / FAILED / REFUNDED / PENDING
    private String displayPaymentStatus;    // UI label: "Received" / "Failed" / "Refunded" / "Pending"
    private String settlementStatus;        // raw: SETTLED / PAYOUT_PENDING / PENDING
    private String displaySettlementStatus; // UI label: "Settled" / "Settlement Pending" / "Pending"
}
