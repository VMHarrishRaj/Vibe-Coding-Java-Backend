package com.truckhire.modules.booking.controller;

import com.truckhire.common.dto.ApiResponse;
import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.util.SecurityUtils;
import com.truckhire.modules.booking.dto.BookingListResponse;
import com.truckhire.modules.booking.dto.BookingResponse;
import com.truckhire.modules.booking.dto.CancelBookingRequest;
import com.truckhire.modules.booking.service.BookingService;
import com.truckhire.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin Booking Controller — admin-only booking management.
 * Base mapping: /admin/bookings
 */
@RestController
@RequestMapping("/admin/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminBookingController {

    private final BookingService bookingService;

    /**
     * GET /admin/bookings — All bookings, paginated, optional ?status= filter.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<BookingListResponse>>> getAllBookings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        PagedResponse<BookingListResponse> response = bookingService.getAllBookings(status, q, pageable);
        return ResponseEntity.ok(ApiResponse.success("Bookings retrieved", response));
    }

    /**
     * GET /admin/bookings/{id} — Booking detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBookingDetail(
            @PathVariable("id") UUID bookingId) {
        BookingResponse response = bookingService.getBookingDetailAdmin(bookingId);
        return ResponseEntity.ok(ApiResponse.success("Booking retrieved", response));
    }

    /**
     * PUT /admin/bookings/{id}/cancel — Cancel any PENDING or CONFIRMED booking.
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @PathVariable("id") UUID bookingId,
            @RequestBody(required = false) CancelBookingRequest request) {
        User admin = SecurityUtils.getCurrentUser();
        String reason = request != null ? request.getReason() : null;
        BookingResponse response = bookingService.adminCancelBooking(admin.getId(), bookingId, reason);
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled", response));
    }
}
