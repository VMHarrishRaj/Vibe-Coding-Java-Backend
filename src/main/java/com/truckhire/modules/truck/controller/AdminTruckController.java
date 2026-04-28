package com.truckhire.modules.truck.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.modules.truck.dto.TruckListResponse;
import com.truckhire.modules.truck.dto.TruckResponse;
import com.truckhire.modules.truck.service.TruckService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/trucks")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTruckController {

    private final TruckService truckService;

    /**
     * GET /api/v1/admin/trucks
     * Optional: ?status=AVAILABLE|UNAVAILABLE|Available|Rented|Not+Available
     *           &vehicleType=MINI|STANDARD|HEAVY
     *           &search=keyword
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> getAllTrucks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(name = "search", required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        PagedResponse<TruckListResponse> trucks = truckService.getAllTrucks(status, vehicleType, q, pageable);
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
}
