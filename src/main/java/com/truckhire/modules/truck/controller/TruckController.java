package com.truckhire.modules.truck.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.truck.dto.*;
import com.truckhire.modules.truck.service.TruckService;
import com.truckhire.modules.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Truck Controller — truck CRUD and photo upload endpoints.
 *
 * ENDPOINTS:
 * POST /trucks → Add truck (Owner, KYC-verified)
 * PUT /trucks/{id} → Update truck (Owner)
 * DELETE /trucks/{id} → Soft-delete truck (Owner)
 * GET /trucks → Search/browse trucks (Any - returns only APPROVED)
 * GET /trucks/{id} → Truck detail (Any)
 * GET /trucks/mine → My trucks (Owner)
 * POST /trucks/{id}/photos → Upload truck photo (Owner)
 * GET /trucks/{id}/documents → List truck documents (Any)
 */
@RestController
@RequestMapping("/trucks")
@RequiredArgsConstructor
public class TruckController {

    private final TruckService truckService;

    /**
     * POST /api/v1/trucks
     * Add a new truck. Only KYC-verified owners can do this.
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckResponse>> addTruck(
            @Valid @RequestBody CreateTruckRequest request) {

        User owner = SecurityUtils.getCurrentUser();
        TruckResponse response = truckService.addTruck(owner.getId(), request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Truck added", response));
    }

    /**
     * PUT /api/v1/trucks/{id}
     * Update truck details (owner only).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckResponse>> updateTruck(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTruckRequest request) {

        User owner = SecurityUtils.getCurrentUser();
        TruckResponse response = truckService.updateTruck(owner.getId(), id, request);

        return ResponseEntity.ok(ApiResponse.success("Truck updated", response));
    }

    /**
     * PUT /api/v1/trucks/{id}/deactivate
     * Deactivate an APPROVED truck (owner only).
     */
    @PutMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> deactivateTruck(@PathVariable UUID id) {
        User owner = SecurityUtils.getCurrentUser();
        truckService.deactivateTruck(owner.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Truck deactivated", null));
    }

    /**
     * DELETE /api/v1/trucks/{id}
     * Soft-delete a truck (owner only).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteTruck(@PathVariable UUID id) {
        User owner = SecurityUtils.getCurrentUser();
        truckService.deleteTruck(owner.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Truck deleted", null));
    }

    /**
     * GET /api/v1/trucks
     * Search/browse trucks. Returns only APPROVED trucks.
     *
     * Query params (all optional):
     *   city          — filter by city (case-insensitive)
     *   vehicleType   — MINI / STANDARD / HEAVY
     *   minPrice      — minimum price per day
     *   maxPrice      — maximum price per day
     *   minCapacity   — minimum capacity in tons
     *   sortBy        — price_asc | price_desc | newest (default)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> searchTrucks(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String sortBy,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<TruckListResponse> trucks = truckService.searchTrucks(
                city, vehicleType, minPrice, maxPrice, minCapacity, sortBy, pageable);

        return ResponseEntity.ok(ApiResponse.success("Trucks retrieved", trucks));
    }

    /**
     * GET /api/v1/trucks/mine
     * Get current owner's trucks (all statuses).
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> getMyTrucks(
            @PageableDefault(size = 20) Pageable pageable) {

        User owner = SecurityUtils.getCurrentUser();
        PagedResponse<TruckListResponse> trucks = truckService.getMyTrucks(
                owner.getId(), pageable);

        return ResponseEntity.ok(ApiResponse.success("My trucks retrieved", trucks));
    }

    /**
     * GET /api/v1/trucks/{id}
     * Get truck detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TruckResponse>> getTruckById(@PathVariable UUID id) {
        TruckResponse response = truckService.getTruckById(id);
        return ResponseEntity.ok(ApiResponse.success("Truck retrieved", response));
    }

    /**
     * POST /api/v1/trucks/{id}/photos
     * Upload truck photo/document. Multipart form-data.
     *
     * If truck is currently APPROVED, uploading new photos reverts
     * status to PENDING_APPROVAL for admin re-review.
     */
    @PostMapping("/{id}/photos")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckDocumentResponse>> uploadTruckPhoto(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentType", defaultValue = "PHOTO") String documentType) {

        User owner = SecurityUtils.getCurrentUser();
        TruckDocumentResponse response = truckService.uploadTruckPhoto(
                owner.getId(), id, documentType, file);

        return ResponseEntity.ok(ApiResponse.success("Truck photo uploaded", response));
    }

    /**
     * GET /api/v1/trucks/{id}/documents
     * List truck documents/photos.
     */
    @GetMapping("/{id}/documents")
    public ResponseEntity<ApiResponse<List<TruckDocumentResponse>>> getTruckDocuments(
            @PathVariable UUID id) {

        List<TruckDocumentResponse> documents = truckService.getTruckDocuments(id);
        return ResponseEntity.ok(ApiResponse.success("Truck documents retrieved", documents));
    }
}
