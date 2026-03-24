package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for GET /admin/payments — paginated invoice list.
 * One row per CHARGE transaction (represents one booking payment).
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
    private BigDecimal total;        // amount charged to renter
    private BigDecimal ownerShare;   // owner_amount (null until payout initiated)
    private BigDecimal platformShare;// platform_fee (null until payout initiated)
    private String paymentStatus;    // SUCCEEDED / FAILED / REFUNDED / PENDING
    private String settlementStatus; // SETTLED / PAYOUT_PENDING / PENDING
}
