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

    // Booking Status widget
    private long ongoingBookings;
    private long completedBookings;
    private long upcomingBookings;
    private long rejectedBookings;

    // Vehicle Availability widget
    private long availableVehicles;
    private long rentedVehicles;
    private long notAvailableVehicles;
}
