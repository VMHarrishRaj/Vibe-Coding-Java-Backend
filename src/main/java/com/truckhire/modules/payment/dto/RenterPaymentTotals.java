package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Aggregate summary included in the GET /payments/mine response for RENTER role.
 * Drives the summary card at the top of the payments screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenterPaymentTotals {

    private BigDecimal totalPaid;        // net across all bookings (refunds already subtracted)
    private int totalTransactions;       // count of booking groups, not raw rows
    private BigDecimal thisMonth;        // amount paid in current calendar month
    private BigDecimal pending;          // sum of PENDING charges not yet captured
}
