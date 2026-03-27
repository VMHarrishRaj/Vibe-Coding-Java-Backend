package com.truckhire.modules.booking.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.booking.dto.*;
import com.truckhire.modules.booking.service.BookingService;
import com.truckhire.modules.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Booking Controller — endpoints for renters and owners.
 * Base mapping: /bookings
 */
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    // ── RENTER endpoints ──

    /**
     * POST /bookings — Renter creates a booking request.
     */
    @PostMapping
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        BookingResponse response = bookingService.createBooking(currentUser.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking request submitted successfully", response));
    }

    /**
     * GET /bookings/mine — Renter's own bookings.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<PagedResponse<BookingListResponse>>> getMyBookingsAsRenter(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User currentUser = SecurityUtils.getCurrentUser();
        PagedResponse<BookingListResponse> response =
                bookingService.getMyBookingsAsRenter(currentUser.getId(), status, pageable);
        return ResponseEntity.ok(ApiResponse.success("Bookings retrieved", response));
    }

    /**
     * PUT /bookings/{id}/cancel — Renter cancels their booking (PENDING or CONFIRMED).
     */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @PathVariable("id") java.util.UUID bookingId,
            @RequestBody(required = false) CancelBookingRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        String reason = request != null ? request.getReason() : null;
        BookingResponse response = bookingService.cancelBooking(currentUser.getId(), bookingId, reason);
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled", response));
    }

    // ── OWNER endpoints ──

    /**
     * GET /bookings/owner/mine — Owner's bookings list.
     */
    @GetMapping("/owner/mine")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PagedResponse<BookingListResponse>>> getMyBookingsAsOwner(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User currentUser = SecurityUtils.getCurrentUser();
        PagedResponse<BookingListResponse> response =
                bookingService.getMyBookingsAsOwner(currentUser.getId(), status, pageable);
        return ResponseEntity.ok(ApiResponse.success("Bookings retrieved", response));
    }

    /**
     * PUT /bookings/{id}/confirm — Owner confirms a PENDING booking.
     */
    @PutMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> confirmBooking(
            @PathVariable("id") java.util.UUID bookingId) {
        User currentUser = SecurityUtils.getCurrentUser();
        BookingResponse response = bookingService.confirmBooking(currentUser.getId(), bookingId);
        return ResponseEntity.ok(ApiResponse.success("Booking confirmed", response));
    }

    /**
     * PUT /bookings/{id}/reject — Owner rejects a PENDING booking.
     */
    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> rejectBooking(
            @PathVariable("id") java.util.UUID bookingId,
            @RequestBody(required = false) RejectBookingRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        String reason = request != null ? request.getReason() : null;
        BookingResponse response = bookingService.rejectBooking(currentUser.getId(), bookingId, reason);
        return ResponseEntity.ok(ApiResponse.success("Booking rejected", response));
    }

    /**
     * PUT /bookings/{id}/owner-cancel — Owner cancels a PENDING or CONFIRMED booking.
     */
    @PutMapping("/{id}/owner-cancel")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> ownerCancelBooking(
            @PathVariable("id") java.util.UUID bookingId,
            @RequestBody(required = false) CancelBookingRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        String reason = request != null ? request.getReason() : null;
        BookingResponse response = bookingService.ownerCancelBooking(currentUser.getId(), bookingId, reason);
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled", response));
    }

    /**
     * PUT /bookings/{id}/handoff — Owner records odometer start → ACTIVE.
     */
    @PutMapping("/{id}/handoff")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> recordHandoff(
            @PathVariable("id") java.util.UUID bookingId,
            @Valid @RequestBody OdometerUpdateRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        BookingResponse response = bookingService.recordOdometerStart(currentUser.getId(), bookingId, request);
        return ResponseEntity.ok(ApiResponse.success("Handoff recorded. Booking is now ACTIVE.", response));
    }

    /**
     * PUT /bookings/{id}/return — Owner records odometer end → COMPLETED.
     */
    @PutMapping("/{id}/return")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> recordReturn(
            @PathVariable("id") java.util.UUID bookingId,
            @Valid @RequestBody OdometerUpdateRequest request) {
        User currentUser = SecurityUtils.getCurrentUser();
        BookingResponse response = bookingService.recordOdometerEnd(currentUser.getId(), bookingId, request);
        return ResponseEntity.ok(ApiResponse.success("Return recorded. Booking is now COMPLETED.", response));
    }

    /**
     * GET /bookings/{id} — Booking detail (renter or owner of this booking).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('RENTER', 'OWNER')")
    public ResponseEntity<ApiResponse<BookingResponse>> getBookingDetail(
            @PathVariable("id") java.util.UUID bookingId) {
        User currentUser = SecurityUtils.getCurrentUser();
        BookingResponse response = bookingService.getBookingDetail(currentUser.getId(), bookingId);
        return ResponseEntity.ok(ApiResponse.success("Booking retrieved", response));
    }
}
