package com.truckhire.modules.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardStatsResponse {
    // KPI cards
    private BigDecimal totalRevenue;
    private long activeBookings;
    private long totalVehicles;
    private long totalClients;
    private long activeOwnerCount;

    // Booking Status widget
    private long totalBookings;       // sum of all booking slices — use as pie chart total
    private long ongoingBookings;
    private long completedBookings;
    private long upcomingBookings;
    private long rejectedBookings;
    private long cancelledBookings;

    // Vehicle Availability widget
    private long availableVehicles;
    private long rentedVehicles;
    private long notAvailableVehicles;
}
