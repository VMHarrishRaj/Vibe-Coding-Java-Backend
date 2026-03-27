package com.truckhire.modules.owner.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.owner.dto.OwnerDashboardResponse;
import com.truckhire.modules.truck.service.TruckService;
import com.truckhire.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner Controller — owner-specific aggregate endpoints.
 *
 * Separated from TruckController because this controller will grow
 * to include booking summaries and earnings data in Phase 5/6.
 *
 * ENDPOINTS:
 * GET /owner/dashboard → Summary of trucks, bookings, earnings
 */
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerController {

    private final TruckService truckService;

    /**
     * GET /api/v1/owner/dashboard
     *
     * Returns a summary for the owner's home screen:
     * - Truck counts by status
     * - Pending bookings (stub: 0 until Phase 5)
     * - Total earnings (stub: 0.00 until Phase 6)
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<OwnerDashboardResponse>> getDashboard() {
        User owner = SecurityUtils.getCurrentUser();
        OwnerDashboardResponse dashboard = truckService.getDashboard(owner.getId());
        return ResponseEntity.ok(ApiResponse.success("Dashboard retrieved", dashboard));
    }
}
