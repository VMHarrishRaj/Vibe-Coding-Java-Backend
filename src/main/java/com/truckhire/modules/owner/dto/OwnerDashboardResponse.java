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

    // ── Booking summary ──
    private long pendingBookings;

    // ── Earnings summary ──
    private BigDecimal totalEarnings;
}
