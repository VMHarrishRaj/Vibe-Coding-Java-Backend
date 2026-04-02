package com.truckhire.modules.addon.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.modules.addon.dto.AdminAddonServiceTypeRequest;
import com.truckhire.modules.addon.dto.AddonServiceTypeResponse;
import com.truckhire.modules.addon.entity.AddonType;
import com.truckhire.modules.addon.service.AddonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/addons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAddonController {

    private final AddonService addonService;

    /**
     * GET /admin/addons?type=INSURANCE&page=0&size=20
     * Lists all addon service types. Optional filter by type.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<AddonServiceTypeResponse>>> listAddons(
            @RequestParam(required = false) AddonType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PagedResponse<AddonServiceTypeResponse> result = addonService.listAddonServiceTypes(
                type,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        return ResponseEntity.ok(ApiResponse.success("Addon services retrieved", result));
    }

    /**
     * POST /admin/addons
     * Create a new Insurance plan or RSA provider.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AddonServiceTypeResponse>> createAddon(
            @Valid @RequestBody AdminAddonServiceTypeRequest request) {
        AddonServiceTypeResponse result = addonService.createAddonServiceType(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Addon service created", result));
    }

    /**
     * PUT /admin/addons/{id}
     * Update an existing addon service type.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AddonServiceTypeResponse>> updateAddon(
            @PathVariable UUID id,
            @Valid @RequestBody AdminAddonServiceTypeRequest request) {
        AddonServiceTypeResponse result = addonService.updateAddonServiceType(id, request);
        return ResponseEntity.ok(ApiResponse.success("Addon service updated", result));
    }

    /**
     * DELETE /admin/addons/{id}
     * Soft-delete an addon service type.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAddon(@PathVariable UUID id) {
        addonService.deleteAddonServiceType(id);
        return ResponseEntity.ok(ApiResponse.success("Addon service deleted", null));
    }
}
