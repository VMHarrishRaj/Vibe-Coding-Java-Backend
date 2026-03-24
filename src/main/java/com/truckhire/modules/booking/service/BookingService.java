package com.truckhire.modules.booking.service;

import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.booking.dto.*;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.booking.entity.BookingStatus;
import com.truckhire.modules.booking.entity.BookingStatusHistory;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.modules.booking.repository.BookingStatusHistoryRepository;
import com.truckhire.modules.payment.service.PaymentService;
import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.entity.TruckStatus;
import com.truckhire.modules.truck.repository.TruckDocumentRepository;
import com.truckhire.modules.truck.repository.TruckRepository;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.entity.UserStatus;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Booking Service — core business logic for the booking lifecycle.
 *
 * COST MODEL:
 * total_cost = (total_days × price_per_day) + (miles_driven × cost_per_mile)
 *
 * STATUS TRANSITIONS:
 * PENDING → CONFIRMED (owner accepts) | REJECTED (owner declines) | CANCELLED
 * CONFIRMED → ACTIVE (owner handoff) | CANCELLED
 * ACTIVE → COMPLETED (owner return)
 */
@Slf4j
@Service
public class BookingService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRepository historyRepository;
    private final TruckRepository truckRepository;
    private final TruckDocumentRepository truckDocumentRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;

    public BookingService(
            BookingRepository bookingRepository,
            BookingStatusHistoryRepository historyRepository,
            TruckRepository truckRepository,
            TruckDocumentRepository truckDocumentRepository,
            UserRepository userRepository,
            @Lazy PaymentService paymentService) {
        this.bookingRepository = bookingRepository;
        this.historyRepository = historyRepository;
        this.truckRepository = truckRepository;
        this.truckDocumentRepository = truckDocumentRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
    }

    // ═══════════════════════════════════════
    // RENTER OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Create a booking request (RENTER only).
     *
     * Guards:
     * 1. Renter must be ACTIVE
     * 2. Renter must have KYC verified
     * 3. Truck must be APPROVED
     * 4. Renter cannot book their own truck
     * 5. Dates must be valid (start >= tomorrow, end > start)
     * 6. No conflicting booking on that truck for those dates
     */
    @Transactional
    public BookingResponse createBooking(UUID renterId, CreateBookingRequest request) {
        User renter = loadActiveUser(renterId);

        if (!renter.isKycVerified()) {
            throw new BusinessException("KYC_NOT_VERIFIED",
                    "Your KYC must be verified before you can make a booking. " +
                            "Please upload your KYC documents and wait for admin verification.");
        }

        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(request.getTruckId())
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", request.getTruckId()));

        if (truck.getStatus() != TruckStatus.APPROVED) {
            throw new BusinessException("TRUCK_NOT_AVAILABLE",
                    "This truck is not available for booking");
        }

        if (truck.getOwner().getId().equals(renterId)) {
            throw new BusinessException("CANNOT_BOOK_OWN_TRUCK",
                    "You cannot book your own truck");
        }

        LocalDate startDate = LocalDate.parse(request.getStartDate());
        LocalDate endDate = LocalDate.parse(request.getEndDate());

        if (!startDate.isAfter(LocalDate.now())) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Start date must be at least tomorrow");
        }
        if (!endDate.isAfter(startDate)) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "End date must be after start date");
        }

        // Exclusive end: 10th–14th = 4 rental days
        int totalDays = (int) ChronoUnit.DAYS.between(startDate, endDate);

        if (bookingRepository.existsConflictingBooking(truck.getId(), startDate, endDate)) {
            throw new BusinessException("TRUCK_NOT_AVAILABLE",
                    "This truck is already booked for the selected dates");
        }

        // Snapshot prices at booking time — never changes after this
        BigDecimal pricePerDay = truck.getPricePerDay();
        BigDecimal costPerMile = truck.getCostPerMile() != null ? truck.getCostPerMile() : BigDecimal.ZERO;
        BigDecimal dayAmount = pricePerDay.multiply(BigDecimal.valueOf(totalDays));

        // Generate human-readable booking number from PostgreSQL sequence
        long seq = bookingRepository.nextBookingSequence();
        String bookingNumber = "BK" + String.format("%03d", seq);

        Booking booking = Booking.builder()
                .bookingNumber(bookingNumber)
                .truck(truck)
                .renter(renter)
                .owner(truck.getOwner())
                .startDate(startDate)
                .endDate(endDate)
                .totalDays(totalDays)
                .pricePerDay(pricePerDay)
                .costPerMile(costPerMile)
                .dayAmount(dayAmount)
                .pickupLocation(request.getPickupLocation())
                .dropoffLocation(request.getDropoffLocation())
                .renterNotes(request.getRenterNotes())
                .status(BookingStatus.PENDING)
                .build();

        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.PENDING, renter, null);

        log.info("Booking created: number={}, truck={}, renter={}", bookingNumber, truck.getId(), renterId);
        return mapToFullResponse(saved);
    }

    /**
     * Renter cancels their own booking.
     * Only PENDING or CONFIRMED bookings can be cancelled — not once ACTIVE.
     */
    @Transactional
    public BookingResponse cancelBooking(UUID renterId, UUID bookingId, String reason) {
        Booking booking = loadBookingWithDetails(bookingId);

        if (!booking.getRenter().getId().equals(renterId)) {
            throw new BusinessException("NOT_BOOKING_RENTER",
                    "You are not the renter for this booking");
        }

        if (booking.getStatus() == BookingStatus.CONFIRMED ||
                booking.getStatus() == BookingStatus.ACTIVE ||
                booking.getStatus() == BookingStatus.COMPLETED ||
                booking.getStatus() == BookingStatus.REJECTED ||
                booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("CANNOT_CANCEL",
                    "Booking cannot be cancelled in status: " + booking.getStatus());
        }

        // Refund if payment was already captured (e.g. cancelling from AWAITING_APPROVAL)
        paymentService.refundIfPaid(bookingId);

        User renter = booking.getRenter();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason);

        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.CANCELLED, renter, reason);

        log.info("Booking cancelled by renter: id={}", bookingId);
        return mapToFullResponse(saved);
    }

    // ═══════════════════════════════════════
    // OWNER OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Owner approves a booking that the renter has already paid for (AWAITING_APPROVAL → CONFIRMED).
     *
     * The renter pays first to demonstrate intent. Once payment is captured, the booking moves to
     * AWAITING_APPROVAL. The owner then decides to approve or reject.
     * If approved: booking proceeds to CONFIRMED → ACTIVE → COMPLETED.
     * If rejected: booking is REJECTED and the renter's payment is refunded.
     */
    @Transactional
    public BookingResponse confirmBooking(UUID ownerId, UUID bookingId) {
        Booking booking = loadBookingWithDetails(bookingId);
        verifyOwner(booking, ownerId);

        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Can only approve bookings in AWAITING_APPROVAL status. Current status: " + booking.getStatus());
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.CONFIRMED, booking.getOwner(), null);

        log.info("Booking approved by owner: id={}, owner={}", bookingId, ownerId);
        return mapToFullResponse(saved);
    }

    /**
     * Owner rejects a booking in AWAITING_APPROVAL status.
     * The renter has already paid — refund is issued automatically.
     */
    @Transactional
    public BookingResponse rejectBooking(UUID ownerId, UUID bookingId, String reason) {
        Booking booking = loadBookingWithDetails(bookingId);
        verifyOwner(booking, ownerId);

        if (booking.getStatus() != BookingStatus.AWAITING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Can only reject bookings in AWAITING_APPROVAL status. Current status: " + booking.getStatus());
        }

        // Renter already paid — refund the charge before rejecting
        paymentService.refundIfPaid(bookingId);

        booking.setStatus(BookingStatus.REJECTED);
        booking.setOwnerNotes(reason);
        booking.setCancelledAt(Instant.now());
        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.REJECTED, booking.getOwner(), reason);

        log.info("Booking rejected by owner (refund issued): id={}, owner={}", bookingId, ownerId);
        return mapToFullResponse(saved);
    }

    /**
     * Owner cancels a booking they own (PENDING, AWAITING_APPROVAL, or CONFIRMED only).
     * Refund is issued automatically if renter has already paid.
     */
    @Transactional
    public BookingResponse ownerCancelBooking(UUID ownerId, UUID bookingId, String reason) {
        Booking booking = loadBookingWithDetails(bookingId);
        verifyOwner(booking, ownerId);

        if (booking.getStatus() == BookingStatus.ACTIVE ||
                booking.getStatus() == BookingStatus.COMPLETED ||
                booking.getStatus() == BookingStatus.REJECTED ||
                booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("CANNOT_CANCEL",
                    "Booking cannot be cancelled in status: " + booking.getStatus());
        }

        // Refund if payment was already captured
        paymentService.refundIfPaid(bookingId);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason);
        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.CANCELLED, booking.getOwner(), reason);

        log.info("Booking cancelled by owner: id={}, owner={}", bookingId, ownerId);
        return mapToFullResponse(saved);
    }

    /**
     * Owner records odometer at truck handoff — transitions booking CONFIRMED → ACTIVE.
     *
     * The odometer reading must be >= the truck's current mileage to catch
     * obviously wrong values. It cannot be less than what the truck shows.
     */
    @Transactional
    public BookingResponse recordOdometerStart(UUID ownerId, UUID bookingId, OdometerUpdateRequest request) {
        Booking booking = loadBookingWithDetails(bookingId);
        verifyOwner(booking, ownerId);

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Can only record handoff for CONFIRMED bookings. Current status: " + booking.getStatus());
        }

        Truck truck = booking.getTruck();
        if (truck.getMileageTotal() != null && request.getOdometerReading() < truck.getMileageTotal()) {
            throw new BusinessException("INVALID_ODOMETER",
                    "Odometer reading (" + request.getOdometerReading() +
                            ") cannot be less than truck's current mileage (" + truck.getMileageTotal() + ")");
        }

        booking.setOdometerStart(request.getOdometerReading());
        booking.setHandedOffAt(Instant.now());
        booking.setStatus(BookingStatus.ACTIVE);
        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.ACTIVE, booking.getOwner(), request.getNotes());

        log.info("Booking handoff recorded: id={}, odometerStart={}", bookingId, request.getOdometerReading());
        return mapToFullResponse(saved);
    }

    /**
     * Owner records odometer at truck return — transitions booking ACTIVE → COMPLETED.
     *
     * Calculates: milesDriven, mileageAmount, totalAmount.
     * Updates truck.mileageTotal with the miles driven on this rental.
     */
    @Transactional
    public BookingResponse recordOdometerEnd(UUID ownerId, UUID bookingId, OdometerUpdateRequest request) {
        Booking booking = loadBookingWithDetails(bookingId);
        verifyOwner(booking, ownerId);

        if (booking.getStatus() != BookingStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "Can only record return for ACTIVE bookings. Current status: " + booking.getStatus());
        }

        if (request.getOdometerReading() < booking.getOdometerStart()) {
            throw new BusinessException("INVALID_ODOMETER",
                    "Return odometer (" + request.getOdometerReading() +
                            ") cannot be less than handoff odometer (" + booking.getOdometerStart() + ")");
        }

        int milesDriven = request.getOdometerReading() - booking.getOdometerStart();
        BigDecimal mileageAmount = BigDecimal.valueOf(milesDriven)
                .multiply(booking.getCostPerMile())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = booking.getDayAmount().add(mileageAmount);

        booking.setOdometerEnd(request.getOdometerReading());
        booking.setMilesDriven(milesDriven);
        booking.setMileageAmount(mileageAmount);
        booking.setTotalAmount(totalAmount);
        booking.setReturnedAt(Instant.now());
        booking.setStatus(BookingStatus.COMPLETED);

        // Update truck's cumulative mileage
        Truck truck = booking.getTruck();
        int currentMileage = truck.getMileageTotal() != null ? truck.getMileageTotal() : 0;
        truck.setMileageTotal(currentMileage + milesDriven);
        truckRepository.save(truck);

        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.COMPLETED, booking.getOwner(), request.getNotes());

        // If there are mileage charges, create a top-up order for the renter to pay.
        // Payout is admin-initiated via POST /admin/bookings/{id}/payout — not automatic.
        if (mileageAmount.compareTo(BigDecimal.ZERO) > 0) {
            paymentService.initiateMileageTopUp(bookingId);
        }

        log.info("Booking completed: id={}, milesDriven={}, totalAmount={}", bookingId, milesDriven, totalAmount);
        return mapToFullResponse(saved);
    }

    // ═══════════════════════════════════════
    // ADMIN OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Admin cancels any PENDING, AWAITING_APPROVAL, or CONFIRMED booking.
     * Refund is issued automatically if renter has already paid.
     */
    @Transactional
    public BookingResponse adminCancelBooking(UUID adminId, UUID bookingId, String reason) {
        Booking booking = loadBookingWithDetails(bookingId);

        if (booking.getStatus() == BookingStatus.ACTIVE ||
                booking.getStatus() == BookingStatus.COMPLETED ||
                booking.getStatus() == BookingStatus.REJECTED ||
                booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException("CANNOT_CANCEL",
                    "Booking cannot be cancelled in status: " + booking.getStatus());
        }

        // Refund if payment was already captured (e.g. CONFIRMED booking)
        paymentService.refundIfPaid(bookingId);

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", adminId));

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancellationReason(reason);
        Booking saved = bookingRepository.save(booking);
        recordHistory(saved, BookingStatus.CANCELLED, admin, reason);

        log.info("Booking cancelled by admin: id={}, adminId={}", bookingId, adminId);
        return mapToFullResponse(saved);
    }

    /**
     * Admin: paginated list of all bookings, optional status filter.
     * Accepts a list of statuses so the "Upcoming" tab can filter on
     * CONFIRMED + AWAITING_APPROVAL simultaneously.
     */
    @Transactional(readOnly = true)
    public PagedResponse<BookingListResponse> getAllBookings(List<String> statusFilters, String q, Pageable pageable) {
        List<BookingStatus> statuses = parseStatusFilters(statusFilters);
        boolean hasQ = q != null && !q.isBlank();
        Page<Booking> page;

        if (hasQ) {
            String keyword = "%" + q.toLowerCase().trim() + "%";
            if (statuses.isEmpty()) {
                page = bookingRepository.searchByKeyword(keyword, pageable);
            } else if (statuses.size() == 1) {
                page = bookingRepository.searchByKeywordAndStatus(keyword, statuses.get(0), pageable);
            } else {
                // Multi-status search not yet implemented — fall back to keyword-only
                page = bookingRepository.searchByKeyword(keyword, pageable);
            }
        } else {
            if (statuses.isEmpty()) {
                page = bookingRepository.findAllWithDetails(pageable);
            } else if (statuses.size() == 1) {
                page = bookingRepository.findAllWithDetailsByStatus(statuses.get(0), pageable);
            } else {
                page = bookingRepository.findAllWithDetailsByStatuses(statuses, pageable);
            }
        }

        return buildListPagedResponse(page);
    }

    /**
     * Admin: full booking detail.
     */
    @Transactional(readOnly = true)
    public BookingResponse getBookingDetailAdmin(UUID bookingId) {
        Booking booking = loadBookingWithDetails(bookingId);
        return mapToFullResponse(booking);
    }

    // ═══════════════════════════════════════
    // RENTER / OWNER LIST QUERIES
    // ═══════════════════════════════════════

    /**
     * Renter's own bookings (paginated, newest first).
     */
    @Transactional(readOnly = true)
    public PagedResponse<BookingListResponse> getMyBookingsAsRenter(UUID renterId, String statusFilter, Pageable pageable) {
        BookingStatus status = parseStatusFilter(statusFilter);
        Page<Booking> page;
        if (status == null) {
            page = bookingRepository.findByRenterIdOrderByCreatedAtDesc(renterId, pageable);
        } else {
            page = bookingRepository.findByRenterIdAndStatusOrderByCreatedAtDesc(renterId, status, pageable);
        }
        return buildListPagedResponse(page);
    }

    /**
     * Owner's bookings (paginated, newest first).
     */
    @Transactional(readOnly = true)
    public PagedResponse<BookingListResponse> getMyBookingsAsOwner(UUID ownerId, String statusFilter, Pageable pageable) {
        BookingStatus status = parseStatusFilter(statusFilter);
        Page<Booking> page;
        if (status == null) {
            page = bookingRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
        } else {
            page = bookingRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, status, pageable);
        }
        return buildListPagedResponse(page);
    }

    /**
     * Full booking detail — accessible by the renter, owner, or admin.
     */
    @Transactional(readOnly = true)
    public BookingResponse getBookingDetail(UUID userId, UUID bookingId) {
        Booking booking = loadBookingWithDetails(bookingId);

        boolean isRenter = booking.getRenter().getId().equals(userId);
        boolean isOwner = booking.getOwner().getId().equals(userId);

        if (!isRenter && !isOwner) {
            // Admin access is handled by a separate endpoint — this check covers renter/owner
            throw new BusinessException("ACCESS_DENIED",
                    "You do not have access to this booking");
        }

        return mapToFullResponse(booking);
    }

    // ═══════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════

    private User loadActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isDeleted() || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("USER_NOT_ACTIVE", "Your account is not active");
        }
        return user;
    }

    private Booking loadBookingWithDetails(UUID bookingId) {
        return bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));
    }

    private void verifyOwner(Booking booking, UUID ownerId) {
        if (!booking.getOwner().getId().equals(ownerId)) {
            throw new BusinessException("NOT_BOOKING_OWNER",
                    "You are not the owner for this booking");
        }
    }

    private void recordHistory(Booking booking, BookingStatus status, User changedBy, String notes) {
        BookingStatusHistory history = BookingStatusHistory.builder()
                .booking(booking)
                .status(status)
                .changedBy(changedBy)
                .notes(notes)
                .build();
        historyRepository.save(history);
    }

    private BookingStatus parseStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) return null;
        try {
            return BookingStatus.valueOf(statusFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<BookingStatus> parseStatusFilters(List<String> statusFilters) {
        if (statusFilters == null || statusFilters.isEmpty()) return List.of();
        return statusFilters.stream()
                .map(s -> {
                    try { return BookingStatus.valueOf(s.toUpperCase()); }
                    catch (IllegalArgumentException e) { return null; }
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());
    }

    private BookingResponse mapToFullResponse(Booking booking) {
        // Load status history
        List<BookingStatusHistory> history = historyRepository
                .findByBookingIdOrderByChangedAtAsc(booking.getId());

        // Cover photo for the truck
        String coverPhotoUrl = null;
        List<Object[]> photos = truckDocumentRepository.findFirstPhotoPerTruck(
                List.of(booking.getTruck().getId()));
        if (!photos.isEmpty()) {
            coverPhotoUrl = baseUrl + "/api/v1/files/" + photos.get(0)[1];
        }

        Truck truck = booking.getTruck();

        return BookingResponse.builder()
                .id(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .status(booking.getStatus().name())
                .createdAt(booking.getCreatedAt() != null ? booking.getCreatedAt().toString() : null)
                .renter(BookingResponse.RenterInfo.builder()
                        .id(booking.getRenter().getId().toString())
                        .fullname(booking.getRenter().getFullname())
                        .phone(booking.getRenter().getPhone())
                        .email(booking.getRenter().getEmail())
                        .build())
                .truck(BookingResponse.TruckInfo.builder()
                        .id(truck.getId().toString())
                        .registrationNumber(truck.getRegistrationNumber())
                        .model(truck.getModel())
                        .make(truck.getMake())
                        .vehicleType(truck.getVehicleType().getName())
                        .locationCity(truck.getLocationCity())
                        .coverPhotoUrl(coverPhotoUrl)
                        .currentMileage(truck.getMileageTotal())
                        .capacityTons(truck.getCapacityTons())
                        .year(truck.getYear())
                        .color(truck.getColor())
                        .fuelType(truck.getFuelType() != null ? truck.getFuelType().name() : null)
                        .vinNumber(truck.getVinNumber())
                        .build())
                .startDate(booking.getStartDate().toString())
                .endDate(booking.getEndDate().toString())
                .totalDays(booking.getTotalDays())
                .odometerStart(booking.getOdometerStart())
                .odometerEnd(booking.getOdometerEnd())
                .milesDriven(booking.getMilesDriven())
                .pickupLocation(booking.getPickupLocation())
                .dropoffLocation(booking.getDropoffLocation())
                .pricePerDay(booking.getPricePerDay())
                .dayAmount(booking.getDayAmount())
                .costPerMile(booking.getCostPerMile())
                .mileageAmount(booking.getMileageAmount())
                .totalAmount(booking.getTotalAmount())
                .insuranceCost(booking.getInsuranceCost())
                .additionalServicesCost(booking.getAdditionalServicesCost())
                .tax(booking.getTax())
                .isOverdue(booking.getStatus() == BookingStatus.ACTIVE
                        && booking.getEndDate().isBefore(java.time.LocalDate.now()))
                .handedOffAt(booking.getHandedOffAt() != null ? booking.getHandedOffAt().toString() : null)
                .returnedAt(booking.getReturnedAt() != null ? booking.getReturnedAt().toString() : null)
                .cancelledAt(booking.getCancelledAt() != null ? booking.getCancelledAt().toString() : null)
                .renterNotes(booking.getRenterNotes())
                .ownerNotes(booking.getOwnerNotes())
                .cancellationReason(booking.getCancellationReason())
                .statusHistory(history.stream()
                        .map(h -> BookingResponse.StatusHistoryEntry.builder()
                                .status(h.getStatus().name())
                                .changedBy(h.getChangedBy().getFullname())
                                .notes(h.getNotes())
                                .changedAt(h.getChangedAt().toString())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private BookingListResponse mapToListResponse(Booking booking) {
        Truck truck = booking.getTruck();
        return BookingListResponse.builder()
                .id(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .renterName(booking.getRenter().getFullname())
                .truckModel(truck.getMake() + " " + truck.getModel())
                .truckId(truck.getId().toString())
                .startDate(booking.getStartDate().toString())
                .endDate(booking.getEndDate().toString())
                .totalDays(booking.getTotalDays())
                .dayAmount(booking.getDayAmount())
                .totalAmount(booking.getTotalAmount())
                .status(booking.getStatus().name())
                .createdAt(booking.getCreatedAt() != null ? booking.getCreatedAt().toString() : null)
                .isOverdue(booking.getStatus() == BookingStatus.ACTIVE
                        && booking.getEndDate().isBefore(java.time.LocalDate.now()))
                .build();
    }

    private PagedResponse<BookingListResponse> buildListPagedResponse(Page<Booking> page) {
        return PagedResponse.<BookingListResponse>builder()
                .content(page.getContent().stream()
                        .map(this::mapToListResponse)
                        .collect(Collectors.toList()))
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}
