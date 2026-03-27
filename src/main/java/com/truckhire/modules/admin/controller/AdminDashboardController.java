package com.truckhire.modules.admin.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.modules.admin.dto.AdminBookingChartResponse;
import com.truckhire.modules.admin.dto.AdminDashboardStatsResponse;
import com.truckhire.modules.admin.dto.AdminRevenueChartResponse;
import com.truckhire.modules.admin.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminDashboardStatsResponse>> getStats() {
        AdminDashboardStatsResponse stats = adminDashboardService.getStats();
        return ResponseEntity.ok(ApiResponse.success("Dashboard stats retrieved", stats));
    }

    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<AdminRevenueChartResponse>> getRevenueChart() {
        AdminRevenueChartResponse chart = adminDashboardService.getRevenueChart();
        return ResponseEntity.ok(ApiResponse.success("Revenue chart data retrieved", chart));
    }

    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<AdminBookingChartResponse>> getBookingChart() {
        AdminBookingChartResponse chart = adminDashboardService.getBookingChart();
        return ResponseEntity.ok(ApiResponse.success("Booking chart data retrieved", chart));
    }
}
