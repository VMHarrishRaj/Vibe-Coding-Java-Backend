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
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Truck Controller — truck CRUD, photo and document upload endpoints.
 *
 * ENDPOINTS:
 * POST   /trucks                    → Add truck (Owner)
 * PUT    /trucks/{id}               → Update truck (Owner)
 * DELETE /trucks/{id}               → Soft-delete truck (Owner)
 * GET    /trucks                    → Search/browse trucks (public)
 * GET    /trucks/{id}               → Truck detail (public)
 * GET    /trucks/mine               → My trucks (Owner)
 * POST   /trucks/{id}/photos        → Upload truck photo (Owner) — documentType defaults to PHOTO
 * GET    /trucks/{id}/photos        → List truck photos only (public)
 * POST   /trucks/{id}/documents     → Upload legal document — RC, INSURANCE, PERMIT (Owner)
 * GET    /trucks/{id}/documents     → List legal documents only — RC, INSURANCE, PERMIT (public)
 */
@RestController
@RequestMapping("/trucks")
@RequiredArgsConstructor
public class TruckController {

    private final TruckService truckService;

    /**
     * POST /api/v1/trucks (JSON)
     * Add a new truck via JSON body. Used by web/admin clients.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckResponse>> addTruckJson(
            @Valid @RequestBody CreateTruckRequest request) {

        User owner = SecurityUtils.getCurrentUser();
        TruckResponse response = truckService.addTruck(owner.getId(), request, null);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Truck added", response));
    }

    /**
     * POST /api/v1/trucks (multipart/form-data)
     * Add a new truck from mobile app — flat form fields + optional photo in one request.
     * Mobile sends field name "image" for the photo.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckResponse>> addTruckMultipart(
            @Valid @ModelAttribute CreateTruckRequest request,
            @RequestParam(value = "image", required = false) MultipartFile photo) {

        User owner = SecurityUtils.getCurrentUser();
        TruckResponse response = truckService.addTruck(owner.getId(), request, photo);

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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate availableFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate availableTo,
            @RequestParam(required = false) Boolean insured,
            @PageableDefault(size = 20) Pageable pageable) {

        PagedResponse<TruckListResponse> trucks = truckService.searchTrucks(
                city, vehicleType, minPrice, maxPrice, minCapacity, sortBy, availableFrom, availableTo, insured, pageable);

        return ResponseEntity.ok(ApiResponse.success("Trucks retrieved", trucks));
    }

    /**
     * GET /api/v1/trucks/mine
     * Get current owner's trucks (all statuses).
     */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PagedResponse<TruckListResponse>>> getMyTrucks(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

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
     * Upload a truck photo. documentType defaults to PHOTO.
     * If truck is currently APPROVED, reverts status to PENDING_APPROVAL for admin re-review.
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
     * GET /api/v1/trucks/{id}/photos
     * List truck photos only. Public — no auth required.
     */
    @GetMapping("/{id}/photos")
    public ResponseEntity<ApiResponse<List<TruckDocumentResponse>>> getTruckPhotos(
            @PathVariable UUID id) {

        List<TruckDocumentResponse> photos = truckService.getTruckPhotos(id);
        return ResponseEntity.ok(ApiResponse.success("Truck photos retrieved", photos));
    }

    /**
     * POST /api/v1/trucks/{id}/documents
     * Upload a legal document (RC, INSURANCE, PERMIT). documentType param required.
     * If truck is currently APPROVED, reverts status to PENDING_APPROVAL for admin re-review.
     */
    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<TruckDocumentResponse>> uploadTruckDocument(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType) {

        User owner = SecurityUtils.getCurrentUser();
        TruckDocumentResponse response = truckService.uploadTruckPhoto(
                owner.getId(), id, documentType, file);

        return ResponseEntity.ok(ApiResponse.success("Truck document uploaded", response));
    }

    /**
     * GET /api/v1/trucks/{id}/documents
     * List truck legal documents only (RC, INSURANCE, PERMIT) — excludes photos.
     * Public — no auth required.
     */
    @GetMapping("/{id}/documents")
    public ResponseEntity<ApiResponse<List<TruckDocumentResponse>>> getTruckDocuments(
            @PathVariable UUID id) {

        List<TruckDocumentResponse> documents = truckService.getTruckDocuments(id);
        return ResponseEntity.ok(ApiResponse.success("Truck documents retrieved", documents));
    }

    /**
     * GET /api/v1/trucks/{id}/booked-dates
     * Returns all upcoming booked date ranges for the calendar date picker.
     * Public — no auth required.
     */
    @GetMapping("/{id}/booked-dates")
    public ResponseEntity<ApiResponse<BookedDatesResponse>> getBookedDates(@PathVariable UUID id) {
        BookedDatesResponse response = truckService.getBookedDates(id);
        return ResponseEntity.ok(ApiResponse.success("Booked dates retrieved", response));
    }

    /**
     * GET /api/v1/trucks/{id}/availability?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
     * Pre-booking validation — check if a truck is available for specific dates.
     * Public — no auth required.
     */
    @GetMapping("/{id}/availability")
    public ResponseEntity<ApiResponse<TruckAvailabilityResponse>> checkAvailability(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        TruckAvailabilityResponse response = truckService.checkAvailability(id, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Availability checked", response));
    }

    /**
     * GET /api/v1/trucks/{id}/calendar?year=2026&month=4
     * Owner: monthly calendar view showing per-day availability status.
     * Each day is AVAILABLE (green, interactive), BLOCKED_BY_OWNER (red, interactive),
     * or BOOKED (red, non-interactive).
     */
    @GetMapping("/{id}/calendar")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<List<CalendarDayResponse>>> getCalendar(
            @PathVariable UUID id,
            @RequestParam int year,
            @RequestParam int month) {

        User owner = SecurityUtils.getCurrentUser();
        List<CalendarDayResponse> days = truckService.getCalendar(owner.getId(), id, year, month);
        return ResponseEntity.ok(ApiResponse.success("Calendar retrieved", days));
    }

    /**
     * POST /api/v1/trucks/{id}/blocked-dates/toggle
     * Owner: toggle a single date — blocks it if available, unblocks if already blocked.
     * Past dates and dates with active bookings are rejected.
     */
    @PostMapping("/{id}/blocked-dates/toggle")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<ToggleBlockedDateResponse>> toggleBlockedDate(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleBlockedDateRequest request) {

        User owner = SecurityUtils.getCurrentUser();
        ToggleBlockedDateResponse response = truckService.toggleBlockedDate(owner.getId(), id, request.getDate());
        return ResponseEntity.ok(ApiResponse.success("Date availability updated", response));
    }

    /**
     * POST /api/v1/trucks/{id}/blocked-dates/batch
     * Owner: commit all staged availability changes in one call (Save Availability button).
     * Accepts two lists: datesToBlock and datesToUnblock.
     * Invalid dates (past or has active booking) are silently skipped and returned in skipped[].
     * Valid changes are always committed even if some dates are skipped.
     */
    @PostMapping("/{id}/blocked-dates/batch")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BatchBlockedDatesResponse>> batchUpdateBlockedDates(
            @PathVariable UUID id,
            @Valid @RequestBody BatchBlockedDatesRequest request) {

        User owner = SecurityUtils.getCurrentUser();
        BatchBlockedDatesResponse response = truckService.batchUpdateBlockedDates(
                owner.getId(), id, request.getDatesToBlock(), request.getDatesToUnblock());
        return ResponseEntity.ok(ApiResponse.success("Availability saved", response));
    }
}
