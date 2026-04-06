package com.truckhire.modules.payment.dto;

import com.truckhire.common.dto.PagedResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response wrapper for GET /admin/payments/by-booking.
 * Combines top-level widget summary stats with the paginated invoice rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaymentsByBookingResponse {

    /** Total revenue collected across ALL SUCCEEDED CHARGE + MILEAGE_TOPUP — same source as /stats KPI. */
    private BigDecimal totalRevenue;

    /** Sum of amounts where ALL transactions for the booking have SUCCEEDED (fully paid bookings). */
    private BigDecimal totalPaid;

    /** Sum of amounts for CHARGE transactions that are still PENDING (not yet captured). */
    private BigDecimal totalPending;

    /** Sum of platform fee across all SUCCEEDED transactions. */
    private BigDecimal totalPlatformShare;

    /** Sum of owner share across all SETTLED (PAID_OUT) payout transactions. */
    private BigDecimal totalOwnerShare;

    /** Paginated list of booking invoice row — one row per booking. */
    private PagedResponse<AdminBookingInvoiceResponse> invoices;
}
