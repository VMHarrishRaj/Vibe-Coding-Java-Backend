package com.truckhire.modules.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Owner Dashboard Response DTO — GET /owner/dashboard
 *
 * Returns a full summary for the owner's home screen:
 * profile, truck counts, booking counts by stage, earnings, and monthly revenue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDashboardResponse {

    // ── Owner profile ──
    private String ownerId;
    private String fullname;
    private String email;
    private String phone;
    private String profileImageUrl;     // null if not uploaded
    private boolean stripeConnected;    // true when Stripe Connect onboarding is complete

    // ── Truck summary ──
    private long totalTrucks;
    private long approvedTrucks;
    private long pendingTrucks;
    private long rejectedTrucks;
    private long inactiveTrucks;

    // ── Booking summary — counts by lifecycle stage ──
    private long awaitingApprovalBookings;  // renter paid, owner has not yet approved
    private long confirmedBookings;         // owner approved, handoff not yet recorded
    private long activeBookings;            // truck currently out on rental
    private long completedBookings;         // rental finished
    private long totalBookings;             // sum of the above four

    // ── Earnings summary ──
    private BigDecimal totalEarnings;           // lifetime: PAID_OUT + PAYOUT_PENDING ownerAmount
    private BigDecimal completedPayoutAmount;   // ownerAmount successfully transferred (PAID_OUT)
    private BigDecimal pendingPayoutAmount;     // ownerAmount sitting in PAYOUT_PENDING (owed, not yet transferred)

    // ── Monthly revenue — last 12 months ──
    // Each entry: { month: "2026-03", revenue: 1200.00 }
    // Months with zero activity are omitted — frontend fills gaps as "No data available"
    private List<MonthlyRevenue> monthlyRevenue;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyRevenue {
        private String month;        // "YYYY-MM" e.g. "2026-03"
        private BigDecimal revenue;  // owner's net earnings (ownerAmount) for that month
    }
}
