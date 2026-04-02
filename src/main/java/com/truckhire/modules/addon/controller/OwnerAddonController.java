package com.truckhire.modules.addon.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.addon.dto.*;
import com.truckhire.modules.addon.service.AddonService;
import com.truckhire.modules.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/owners/me/trucks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerAddonController {

    private final AddonService addonService;

    // ── Insurance ──────────────────────────────────────────────────────────────

    /**
     * GET /owners/me/trucks/{truckId}/insurance
     * Returns the active insurance plan(s) attached to this truck.
     */
    @GetMapping("/{truckId}/insurance")
    public ResponseEntity<ApiResponse<List<TruckInsuranceResponse>>> getInsurance(
            @PathVariable UUID truckId) {
        User owner = SecurityUtils.getCurrentUser();
        List<TruckInsuranceResponse> result = addonService.getTruckInsurance(owner.getId(), truckId);
        return ResponseEntity.ok(ApiResponse.success("Insurance retrieved", result));
    }

    /**
     * POST /owners/me/trucks/{truckId}/insurance
     * Attach an insurance plan to this truck. Sets trucks.insured = true.
     */
    @PostMapping("/{truckId}/insurance")
    public ResponseEntity<ApiResponse<TruckInsuranceResponse>> attachInsurance(
            @PathVariable UUID truckId,
            @Valid @RequestBody TruckInsuranceRequest request) {
        User owner = SecurityUtils.getCurrentUser();
        TruckInsuranceResponse result = addonService.attachInsurance(owner.getId(), truckId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Insurance attached to truck", result));
    }

    /**
     * DELETE /owners/me/trucks/{truckId}/insurance/{insuranceId}
     * Detach an insurance plan. Sets trucks.insured = false if none remain.
     */
    @DeleteMapping("/{truckId}/insurance/{insuranceId}")
    public ResponseEntity<ApiResponse<Void>> detachInsurance(
            @PathVariable UUID truckId,
            @PathVariable UUID insuranceId) {
        User owner = SecurityUtils.getCurrentUser();
        addonService.detachInsurance(owner.getId(), truckId, insuranceId);
        return ResponseEntity.ok(ApiResponse.success("Insurance detached", null));
    }

    // ── Equipment ──────────────────────────────────────────────────────────────

    /**
     * GET /owners/me/trucks/{truckId}/equipment
     * List all equipment registered for this truck.
     */
    @GetMapping("/{truckId}/equipment")
    public ResponseEntity<ApiResponse<List<TruckEquipmentResponse>>> listEquipment(
            @PathVariable UUID truckId) {
        User owner = SecurityUtils.getCurrentUser();
        List<TruckEquipmentResponse> result = addonService.listEquipment(owner.getId(), truckId);
        return ResponseEntity.ok(ApiResponse.success("Equipment retrieved", result));
    }

    /**
     * POST /owners/me/trucks/{truckId}/equipment
     * Register a new equipment item for this truck.
     */
    @PostMapping("/{truckId}/equipment")
    public ResponseEntity<ApiResponse<TruckEquipmentResponse>> addEquipment(
            @PathVariable UUID truckId,
            @Valid @RequestBody TruckEquipmentRequest request) {
        User owner = SecurityUtils.getCurrentUser();
        TruckEquipmentResponse result = addonService.addEquipment(owner.getId(), truckId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Equipment added", result));
    }

    /**
     * PUT /owners/me/trucks/{truckId}/equipment/{equipmentId}
     * Update an equipment item.
     */
    @PutMapping("/{truckId}/equipment/{equipmentId}")
    public ResponseEntity<ApiResponse<TruckEquipmentResponse>> updateEquipment(
            @PathVariable UUID truckId,
            @PathVariable UUID equipmentId,
            @Valid @RequestBody TruckEquipmentRequest request) {
        User owner = SecurityUtils.getCurrentUser();
        TruckEquipmentResponse result = addonService.updateEquipment(owner.getId(), truckId, equipmentId, request);
        return ResponseEntity.ok(ApiResponse.success("Equipment updated", result));
    }

    /**
     * DELETE /owners/me/trucks/{truckId}/equipment/{equipmentId}
     * Soft-delete an equipment item.
     */
    @DeleteMapping("/{truckId}/equipment/{equipmentId}")
    public ResponseEntity<ApiResponse<Void>> deleteEquipment(
            @PathVariable UUID truckId,
            @PathVariable UUID equipmentId) {
        User owner = SecurityUtils.getCurrentUser();
        addonService.deleteEquipment(owner.getId(), truckId, equipmentId);
        return ResponseEntity.ok(ApiResponse.success("Equipment deleted", null));
    }
}
