package com.truckhire.modules.addon.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.addon.dto.BookingAddonSummary;
import com.truckhire.modules.addon.dto.TruckAddonsResponse;
import com.truckhire.modules.addon.service.AddonService;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RenterAddonController {

    private final AddonService addonService;
    private final BookingRepository bookingRepository;

    /**
     * GET /trucks/{truckId}/addons
     * Public — shows insurance, RSA options, and available equipment for a truck.
     * Renters use this before creating a booking to know what they can select.
     */
    @GetMapping("/trucks/{truckId}/addons")
    public ResponseEntity<ApiResponse<TruckAddonsResponse>> getTruckAddons(
            @PathVariable UUID truckId) {
        TruckAddonsResponse result = addonService.getTruckAddons(truckId);
        return ResponseEntity.ok(ApiResponse.success("Truck addons retrieved", result));
    }

    /**
     * GET /bookings/{id}/addons
     * Authenticated — returns selected addons for the renter's booking.
     * RSA addons include provider contact info for post-booking help.
     */
    @GetMapping("/bookings/{id}/addons")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<BookingAddonSummary>>> getBookingAddons(
            @PathVariable UUID id) {
        User currentUser = SecurityUtils.getCurrentUser();

        Booking booking = bookingRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", id));

        // Only the renter or owner of this booking can see its addons
        boolean isRenter = booking.getRenter().getId().equals(currentUser.getId());
        boolean isOwner = booking.getOwner().getId().equals(currentUser.getId());
        if (!isRenter && !isOwner) {
            throw new BusinessException("ACCESS_DENIED", "You do not have access to this booking");
        }

        List<BookingAddonSummary> addons = addonService.getBookingAddons(id);
        return ResponseEntity.ok(ApiResponse.success("Booking addons retrieved", addons));
    }
}
