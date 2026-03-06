package com.truckhire.modules.truck.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.truck.dto.TruckListResponse;
import com.truckhire.modules.truck.dto.TruckResponse;
import com.truckhire.modules.truck.service.TruckService;
import com.truckhire.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Truck Controller — admin-only truck management endpoints.
 *
 * ENDPOINTS:
 * GET /admin/trucks → List all trucks (any status)
 * GET /admin/trucks/{id} → Truck detail
 * PUT /admin/trucks/{id}/approve → Approve truck
 * PUT /admin/trucks/{id}/reject → Reject truck
 * GET /admin/trucks/pending → List pending-approval trucks
 */
@RestController
@RequestMapping("/admin/trucks")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTruckController {

    private final TruckService truckService;

    /**
     * GET /api/v1/admin/trucks
     * Optional: ?status=APPROVED|REJECTED|PENDING_APPROVAL|INACTIVE
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> getAllTrucks(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<TruckListResponse> trucks = truckService.getAllTrucks(status, pageable);
        return ResponseEntity.ok(ApiResponse.success("All trucks retrieved", trucks));
    }

    /**
     * GET /api/v1/admin/trucks/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TruckResponse>> getTruckById(@PathVariable UUID id) {
        TruckResponse response = truckService.getTruckById(id);
        return ResponseEntity.ok(ApiResponse.success("Truck retrieved", response));
    }

    /**
     * GET /api/v1/admin/trucks/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> getPendingTrucks(
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<TruckListResponse> trucks = truckService.getPendingTrucks(pageable);
        return ResponseEntity.ok(ApiResponse.success("Pending trucks retrieved", trucks));
    }

    /**
     * PUT /api/v1/admin/trucks/{id}/approve
     */
    @PutMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<Void>> approveTruck(@PathVariable UUID id) {
        User admin = SecurityUtils.getCurrentUser();
        truckService.approveTruck(id, admin.getId());
        return ResponseEntity.ok(ApiResponse.success("Truck approved", null));
    }

    /**
     * PUT /api/v1/admin/trucks/{id}/reject
     */
    @PutMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<Void>> rejectTruck(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {

        User admin = SecurityUtils.getCurrentUser();
        truckService.rejectTruck(id, admin.getId(), reason);
        return ResponseEntity.ok(ApiResponse.success("Truck rejected", null));
    }
}
