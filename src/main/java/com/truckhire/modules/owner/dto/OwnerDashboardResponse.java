package com.truckhire.modules.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Owner Dashboard Response DTO — GET /owner/dashboard
 *
 * Returns a summary of the owner's activity on the platform.
 *
 * DESIGN DECISION (stub fields):
 * pendingBookings and totalEarnings are returned as 0 / 0.00 until
 * Phase 5 (Booking Engine) and Phase 6 (Payments) are implemented.
 * The endpoint contract is established now so the mobile team can
 * build against a stable shape — the numbers will fill in later
 * without any API change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDashboardResponse {

    // ── Truck summary ──
    private long totalTrucks;
    private long approvedTrucks;
    private long pendingTrucks;
    private long rejectedTrucks;
    private long inactiveTrucks;

    // ── Booking summary (Phase 5 stub) ──
    private long pendingBookings;

    // ── Earnings summary (Phase 6 stub) ──
    private BigDecimal totalEarnings;
}
