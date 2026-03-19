package com.truckhire.modules.truck.service;

import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.common.storage.FileStorageService;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.booking.entity.BookingStatus;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.modules.owner.dto.OwnerDashboardResponse;
import com.truckhire.modules.truck.dto.*;
import com.truckhire.modules.truck.entity.*;
import com.truckhire.modules.truck.repository.*;
import com.truckhire.modules.user.entity.DocumentType;
import com.truckhire.modules.user.entity.Role;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.repository.DocumentTypeRepository;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Truck Service — business logic for truck CRUD, photo uploads, and admin
 * approval.
 *
 * CRITICAL BUSINESS RULES:
 * 1. Only KYC-verified owners can add/manage trucks
 * 2. New trucks start as PENDING_APPROVAL
 * 3. Only APPROVED trucks are visible to renters in search
 * 4. When owner uploads new photos to an APPROVED truck, status reverts to
 * PENDING_APPROVAL
 * 5. Only the truck owner can edit/delete their own trucks
 * 6. Admin can approve/reject trucks
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TruckService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final TruckRepository truckRepository;
    private final TruckDocumentRepository truckDocumentRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final BookingRepository bookingRepository;

    // ═══════════════════════════════════════
    // OWNER OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Add a new truck.
     *
     * GUARDS:
     * - User must be an OWNER
     * - Owner must have KYC verified (kyc_verified = true)
     * - Registration number must be unique
     */
    @Transactional
    public TruckResponse addTruck(UUID ownerId, CreateTruckRequest request, MultipartFile photo) {
        User owner = findOwner(ownerId);
        ensureKycVerified(owner);

        // Check unique registration number (only among non-deleted trucks)
        if (truckRepository.existsByRegistrationNumberAndDeletedAtIsNull(request.getRegistrationNumber())) {
            throw new BusinessException("REGISTRATION_NUMBER_TAKEN",
                    "A truck with this registration number already exists");
        }

        // Look up vehicle type
        VehicleType vehicleType = vehicleTypeRepository
                .findByName(request.getVehicleType().toUpperCase())
                .orElseThrow(() -> new BusinessException("INVALID_VEHICLE_TYPE",
                        "Invalid vehicle type: " + request.getVehicleType()));

        Truck truck = Truck.builder()
                .owner(owner)
                .vehicleType(vehicleType)
                .registrationNumber(request.getRegistrationNumber().toUpperCase().trim())
                .model(request.getModel().trim())
                .make(request.getMake().trim())
                .pricePerDay(request.getPricePerDay())
                .costPerMile(request.getCostPerMile())
                .locationCity(request.getLocationCity().trim())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .capacityTons(request.getCapacityTons())
                .torque(request.getTorque())
                .description(request.getDescription())
                .status(TruckStatus.PENDING_APPROVAL)
                .build();

        Truck saved = truckRepository.save(truck);
        log.info("Truck added: id={}, owner={}, reg={}",
                saved.getId(), ownerId, saved.getRegistrationNumber());

        // If a photo was included with the creation request, store it immediately
        if (photo != null && !photo.isEmpty()) {
            DocumentType photoDocType = documentTypeRepository
                    .findByNameAndCategory("PHOTO", "VEHICLE")
                    .orElseThrow(() -> new BusinessException("INVALID_DOCUMENT_TYPE", "PHOTO document type not found"));
            String subDirectory = "trucks/" + saved.getId();
            String filePath = fileStorageService.storeFile(photo, subDirectory);
            TruckDocument doc = TruckDocument.builder()
                    .truck(saved)
                    .documentType(photoDocType)
                    .filePath(filePath)
                    .build();
            truckDocumentRepository.save(doc);
            log.info("Truck photo saved inline: truckId={}, path={}", saved.getId(), filePath);
        }

        return mapToResponse(saved);
    }

    /**
     * Update a truck (owner only, partial update).
     * Registration number and vehicle type cannot be changed after creation.
     */
    @Transactional
    public TruckResponse updateTruck(UUID ownerId, UUID truckId, UpdateTruckRequest request) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        if (request.getModel() != null)
            truck.setModel(request.getModel().trim());
        if (request.getMake() != null)
            truck.setMake(request.getMake().trim());
        if (request.getPricePerDay() != null)
            truck.setPricePerDay(request.getPricePerDay());
        if (request.getCostPerMile() != null)
            truck.setCostPerMile(request.getCostPerMile());
        if (request.getLocationCity() != null)
            truck.setLocationCity(request.getLocationCity().trim());
        if (request.getLatitude() != null)
            truck.setLatitude(request.getLatitude());
        if (request.getLongitude() != null)
            truck.setLongitude(request.getLongitude());
        if (request.getCapacityTons() != null)
            truck.setCapacityTons(request.getCapacityTons());
        if (request.getTorque() != null)
            truck.setTorque(request.getTorque());
        if (request.getDescription() != null)
            truck.setDescription(request.getDescription());

        Truck saved = truckRepository.save(truck);
        log.info("Truck updated: id={}", truckId);
        return mapToResponse(saved);
    }

    /**
     * Deactivate an APPROVED truck (owner only).
     * Only APPROVED trucks can be deactivated — PENDING/REJECTED stay as-is.
     */
    @Transactional
    public void deactivateTruck(UUID ownerId, UUID truckId) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        if (truck.getStatus() != TruckStatus.APPROVED) {
            throw new BusinessException("TRUCK_NOT_APPROVED",
                    "Only APPROVED trucks can be deactivated");
        }

        truck.setStatus(TruckStatus.INACTIVE);
        truckRepository.save(truck);
        log.info("Truck deactivated: id={}, owner={}", truckId, ownerId);
    }

    /**
     * Soft-delete a truck (owner only).
     */
    @Transactional
    public void deleteTruck(UUID ownerId, UUID truckId) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);
        truck.softDelete();
        truckRepository.save(truck);
        log.info("Truck soft-deleted: id={}, owner={}", truckId, ownerId);
    }

    /**
     * Upload a truck photo/document.
     *
     * IMPORTANT: If the truck is currently APPROVED, uploading new photos
     * reverts status to PENDING_APPROVAL so admin can re-verify.
     */
    @Transactional
    public TruckDocumentResponse uploadTruckPhoto(UUID ownerId, UUID truckId,
            String documentType, MultipartFile file) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        // Default document type to PHOTO if not specified
        String docTypeName = (documentType != null) ? documentType.toUpperCase() : "PHOTO";

        DocumentType docType = documentTypeRepository
                .findByNameAndCategory(docTypeName, "VEHICLE")
                .orElseThrow(() -> new BusinessException("INVALID_DOCUMENT_TYPE",
                        "Invalid vehicle document type: " + docTypeName));

        // Store file: uploads/trucks/{truckId}/{uuid}_filename.jpg
        String subDirectory = "trucks/" + truckId;
        String filePath = fileStorageService.storeFile(file, subDirectory);

        TruckDocument document = TruckDocument.builder()
                .truck(truck)
                .documentType(docType)
                .filePath(filePath)
                .build();

        TruckDocument saved = truckDocumentRepository.save(document);

        // If truck was APPROVED, revert to PENDING_APPROVAL for re-review
        if (truck.getStatus() == TruckStatus.APPROVED) {
            truck.setStatus(TruckStatus.PENDING_APPROVAL);
            truckRepository.save(truck);
            log.info("Truck status reverted to PENDING_APPROVAL after photo upload: id={}", truckId);
        }

        log.info("Truck photo uploaded: truckId={}, docType={}, path={}",
                truckId, docTypeName, filePath);

        return mapToDocumentResponse(saved);
    }

    /**
     * Get owner's trucks (paginated).
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getMyTrucks(UUID ownerId, Pageable pageable) {
        Page<Truck> page = truckRepository.findByOwnerIdAndDeletedAtIsNull(ownerId, pageable);
        return buildPagedResponse(page);
    }

    /**
     * Get owner dashboard summary.
     *
     * Counts trucks per status in one GROUP BY query, then maps results
     * into the dashboard DTO. Booking and earnings fields are stubbed at 0
     * until Phase 5/6 are implemented.
     */
    @Transactional(readOnly = true)
    public OwnerDashboardResponse getDashboard(UUID ownerId) {
        List<Object[]> rows = truckRepository.countTrucksByStatusForOwner(ownerId);

        long approved = 0, pending = 0, rejected = 0, inactive = 0;
        for (Object[] row : rows) {
            TruckStatus status = (TruckStatus) row[0];
            long count = (long) row[1];
            switch (status) {
                case APPROVED        -> approved = count;
                case PENDING_APPROVAL -> pending = count;
                case REJECTED        -> rejected = count;
                case INACTIVE        -> inactive = count;
            }
        }

        long pendingBookings = bookingRepository.countByOwnerIdAndStatus(ownerId, BookingStatus.PENDING);
        BigDecimal totalEarnings = bookingRepository.sumTotalAmountByOwnerIdAndCompleted(ownerId);

        return OwnerDashboardResponse.builder()
                .totalTrucks(approved + pending + rejected + inactive)
                .approvedTrucks(approved)
                .pendingTrucks(pending)
                .rejectedTrucks(rejected)
                .inactiveTrucks(inactive)
                .pendingBookings(pendingBookings)
                .totalEarnings(totalEarnings)
                .build();
    }

    // ═══════════════════════════════════════
    // PUBLIC OPERATIONS (Renter search)
    // ═══════════════════════════════════════

    /**
     * Search/browse trucks (public).
     *
     * Returns APPROVED, INACTIVE, and PENDING_APPROVAL trucks (REJECTED are hidden).
     * After fetching the page, availability is enriched in one extra batch query:
     * - APPROVED + no active/upcoming booking → "AVAILABLE"
     * - APPROVED + CONFIRMED/ACTIVE booking today or future → "RENTED" + rentedUntil
     * - INACTIVE → "UNAVAILABLE" (deactivated by owner)
     * - PENDING_APPROVAL → "UNAVAILABLE" (pending admin review)
     *
     * This is one extra query per page — not N+1.
     *
     * All filters are optional — pass null to skip.
     * sortBy controls ordering: price_asc, price_desc, newest (default).
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> searchTrucks(
            String city, String vehicleType,
            BigDecimal minPrice, BigDecimal maxPrice,
            Integer minCapacity, String sortBy,
            LocalDate availableFrom, LocalDate availableTo,
            Pageable pageable) {

        // ── Date range validation ──
        // Both must be provided together or both omitted.
        if ((availableFrom == null) != (availableTo == null)) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Both availableFrom and availableTo must be provided together");
        }
        if (availableFrom != null) {
            if (availableFrom.isBefore(LocalDate.now())) {
                throw new BusinessException("INVALID_DATE_RANGE",
                        "availableFrom cannot be in the past");
            }
            if (!availableTo.isAfter(availableFrom)) {
                throw new BusinessException("INVALID_DATE_RANGE",
                        "availableTo must be after availableFrom");
            }
        }

        Sort sort = resolveSort(sortBy);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        String cityLower = city != null && !city.isBlank() ? city.trim().toLowerCase() : null;
        String vehicleTypeNorm = vehicleType != null && !vehicleType.isBlank() ? vehicleType.toUpperCase() : null;

        Page<Truck> page;
        if (availableFrom != null) {
            page = truckRepository.searchPublicTrucksWithDates(
                    cityLower, vehicleTypeNorm, minPrice, maxPrice, minCapacity,
                    availableFrom, availableTo, sortedPageable);
        } else {
            page = truckRepository.searchPublicTrucks(
                    cityLower, vehicleTypeNorm, minPrice, maxPrice, minCapacity,
                    sortedPageable);
        }

        return buildPagedResponseWithAvailability(page);
    }

    /**
     * Resolve sortBy string to a Spring Data Sort object.
     * Defaults to newest (createdAt DESC) if sortBy is null or unrecognized.
     */
    private Sort resolveSort(String sortBy) {
        if (sortBy == null) return Sort.by(Sort.Direction.DESC, "createdAt");
        return switch (sortBy.toLowerCase()) {
            case "price_asc"  -> Sort.by(Sort.Direction.ASC,  "pricePerDay");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "pricePerDay");
            default           -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    /**
     * Get truck detail (any user).
     * Enriches with cover photo URL and current availability status.
     */
    @Transactional(readOnly = true)
    public TruckResponse getTruckById(UUID truckId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        // Cover photo — reuse the same batch query with a single-element list
        String coverPhotoUrl = null;
        List<Object[]> photos = truckDocumentRepository.findFirstPhotoPerTruck(List.of(truckId));
        if (!photos.isEmpty()) {
            coverPhotoUrl = baseUrl + "/api/v1/files/" + photos.get(0)[1];
        }

        // Availability — reuse the same CONFIRMED/ACTIVE batch query with a single-element list
        List<Booking> bookings = bookingRepository.findCurrentOrUpcomingBookingsByTruckIds(List.of(truckId));
        Booking activeBooking = bookings.isEmpty() ? null : bookings.get(0);

        return mapToDetailResponse(truck, coverPhotoUrl, activeBooking);
    }

    /**
     * Get all upcoming booked date ranges for a truck (public, for calendar date picker).
     * Includes PENDING bookings so two renters cannot both select the same dates.
     */
    @Transactional(readOnly = true)
    public BookedDatesResponse getBookedDates(UUID truckId) {
        truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        List<Booking> bookings = bookingRepository.findUpcomingBookingsByTruckId(truckId);

        List<BookedDatesResponse.BookedRange> ranges = bookings.stream()
                .map(b -> BookedDatesResponse.BookedRange.builder()
                        .startDate(b.getStartDate().toString())
                        .endDate(b.getEndDate().toString())
                        .status(b.getStatus().name())
                        .build())
                .toList();

        return BookedDatesResponse.builder()
                .truckId(truckId.toString())
                .bookedRanges(ranges)
                .build();
    }

    /**
     * Check whether a truck is available for a specific date range (public, pre-booking validation).
     * Validates that startDate < endDate and startDate >= today.
     * Returns the conflicting booking's date range when unavailable.
     */
    @Transactional(readOnly = true)
    public TruckAvailabilityResponse checkAvailability(UUID truckId, LocalDate startDate, LocalDate endDate) {
        truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        if (!startDate.isBefore(endDate)) {
            throw new BusinessException("INVALID_DATE_RANGE", "startDate must be before endDate");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new BusinessException("INVALID_DATE_RANGE", "startDate cannot be in the past");
        }

        boolean hasConflict = bookingRepository.existsConflictingBooking(truckId, startDate, endDate);

        if (!hasConflict) {
            return TruckAvailabilityResponse.builder()
                    .truckId(truckId.toString())
                    .available(true)
                    .startDate(startDate.toString())
                    .endDate(endDate.toString())
                    .build();
        }

        // Find the first conflicting booking to return its range to the frontend
        TruckAvailabilityResponse.ConflictingRange conflictRange = bookingRepository
                .findUpcomingBookingsByTruckId(truckId)
                .stream()
                .filter(b -> !b.getStartDate().isAfter(endDate) && !b.getEndDate().isBefore(startDate))
                .findFirst()
                .map(b -> TruckAvailabilityResponse.ConflictingRange.builder()
                        .startDate(b.getStartDate().toString())
                        .endDate(b.getEndDate().toString())
                        .build())
                .orElse(null);

        return TruckAvailabilityResponse.builder()
                .truckId(truckId.toString())
                .available(false)
                .startDate(startDate.toString())
                .endDate(endDate.toString())
                .conflictingRange(conflictRange)
                .build();
    }

    /**
     * Get truck documents/photos.
     */
    @Transactional(readOnly = true)
    public List<TruckDocumentResponse> getTruckDocuments(UUID truckId) {
        // Verify truck exists
        truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        return truckDocumentRepository.findByTruckId(truckId)
                .stream()
                .map(this::mapToDocumentResponse)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════
    // ADMIN OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Admin: List all trucks, optionally filtered by status and/or free-text keyword.
     * q searches: registrationNumber, model, make, owner name (case-insensitive LIKE).
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getAllTrucks(String status, String q, Pageable pageable) {
        Page<Truck> page;
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasQ      = q != null && !q.isBlank();

        TruckStatus truckStatus = null;
        if (hasStatus) {
            try {
                truckStatus = TruckStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Unrecognized status — treat as no filter rather than erroring.
                hasStatus = false;
            }
        }

        if (hasQ) {
            String keyword = "%" + q.toLowerCase().trim() + "%";
            page = hasStatus
                    ? truckRepository.searchByKeywordAndStatus(keyword, truckStatus, pageable)
                    : truckRepository.searchByKeyword(keyword, pageable);
        } else if (hasStatus) {
            page = truckRepository.findByStatusActiveWithOwnerNoOrder(truckStatus, pageable);
        } else {
            page = truckRepository.findAllActiveWithOwner(pageable);
        }

        return buildPagedResponse(page);
    }

    /**
     * Admin: List pending-approval trucks.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getPendingTrucks(Pageable pageable) {
        Page<Truck> page = truckRepository.findByStatusActiveWithOwner(
                TruckStatus.PENDING_APPROVAL, pageable);
        return buildPagedResponse(page);
    }

    /**
     * Admin: Approve a truck.
     */
    @Transactional
    public void approveTruck(UUID truckId, UUID adminId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        if (truck.getStatus() == TruckStatus.APPROVED) {
            throw new BusinessException("ALREADY_APPROVED", "Truck is already approved");
        }

        truck.setStatus(TruckStatus.APPROVED);
        truckRepository.save(truck);
        log.info("Truck approved: id={}, by adminId={}", truckId, adminId);
    }

    /**
     * Admin: Reject a truck.
     */
    @Transactional
    public void rejectTruck(UUID truckId, UUID adminId, String reason) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        if (truck.getStatus() == TruckStatus.REJECTED) {
            throw new BusinessException("ALREADY_REJECTED", "Truck is already rejected");
        }

        truck.setStatus(TruckStatus.REJECTED);
        truck.setRejectionReason(reason);
        truckRepository.save(truck);
        log.info("Truck rejected: id={}, by adminId={}, reason={}",
                truckId, adminId, reason);
    }

    // ═══════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════

    /**
     * Find a user and verify they are an OWNER.
     */
    private User findOwner(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (user.isDeleted()) {
            throw new ResourceNotFoundException("User", "id", userId);
        }

        if (!Role.OWNER.equals(user.getRole().getName())) {
            throw new BusinessException("NOT_OWNER",
                    "Only owners can manage trucks");
        }

        return user;
    }

    /**
     * Verify the owner's KYC is verified.
     * This is the backend guard that complements the frontend restriction.
     */
    private void ensureKycVerified(User owner) {
        if (!owner.isKycVerified()) {
            throw new BusinessException("KYC_NOT_VERIFIED",
                    "Your KYC must be verified before you can add trucks. " +
                            "Please upload your KYC documents and wait for admin verification.");
        }
    }

    /**
     * Find a truck and verify it belongs to the given owner.
     */
    private Truck findTruckOwnedBy(UUID truckId, UUID ownerId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        if (!truck.getOwner().getId().equals(ownerId)) {
            throw new BusinessException("NOT_TRUCK_OWNER",
                    "You do not own this truck");
        }

        return truck;
    }

    private TruckResponse mapToResponse(Truck truck) {
        return mapToDetailResponse(truck, null, null);
    }

    /**
     * Build a TruckResponse with optional cover photo URL and availability enrichment.
     */
    private TruckResponse mapToDetailResponse(Truck truck, String coverPhotoUrl, Booking activeBooking) {
        TruckResponse.TruckResponseBuilder builder = TruckResponse.builder()
                .id(truck.getId().toString())
                .ownerId(truck.getOwner().getId().toString())
                .ownerName(truck.getOwner().getFullname())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay())
                .costPerMile(truck.getCostPerMile())
                .locationCity(truck.getLocationCity())
                .latitude(truck.getLatitude())
                .longitude(truck.getLongitude())
                .capacityTons(truck.getCapacityTons())
                .torque(truck.getTorque())
                .mileageTotal(truck.getMileageTotal())
                .status(truck.getStatus().name())
                .rejectionReason(truck.getRejectionReason())
                .description(truck.getDescription())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .coverPhotoUrl(coverPhotoUrl);

        // Availability enrichment — same logic as enrichAvailability() for TruckListResponse
        switch (truck.getStatus()) {
            case APPROVED -> {
                if (activeBooking == null) {
                    builder.availabilityStatus("AVAILABLE");
                } else {
                    builder.availabilityStatus("RENTED");
                    builder.rentedUntil(activeBooking.getEndDate().toString());
                }
            }
            case INACTIVE -> {
                builder.availabilityStatus("UNAVAILABLE");
                builder.unavailableReason("Deactivated by owner");
            }
            case PENDING_APPROVAL -> {
                builder.availabilityStatus("UNAVAILABLE");
                builder.unavailableReason("Pending approval");
            }
            default -> builder.availabilityStatus("UNAVAILABLE");
        }

        return builder.build();
    }

    private TruckListResponse mapToListResponse(Truck truck, String coverPhotoUrl) {
        return TruckListResponse.builder()
                .id(truck.getId().toString())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay())
                .locationCity(truck.getLocationCity())
                .capacityTons(truck.getCapacityTons())
                .status(truck.getStatus().name())
                .ownerName(truck.getOwner().getFullname())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .coverPhotoUrl(coverPhotoUrl)
                .build();
    }

    private TruckDocumentResponse mapToDocumentResponse(TruckDocument doc) {
        return TruckDocumentResponse.builder()
                .id(doc.getId().toString())
                .documentType(doc.getDocumentType().getName())
                .filePath(doc.getFilePath())
                .fileUrl(baseUrl + "/api/v1/files/" + doc.getFilePath())
                .uploadedAt(doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null)
                .build();
    }

    private PagedResponse<TruckListResponse> buildPagedResponse(Page<Truck> page) {
        List<UUID> truckIds = page.getContent().stream()
                .map(Truck::getId)
                .collect(Collectors.toList());

        // Build truckId -> coverPhotoUrl map in one query (no N+1)
        Map<UUID, String> coverPhotos = new HashMap<>();
        if (!truckIds.isEmpty()) {
            List<Object[]> rows = truckDocumentRepository.findFirstPhotoPerTruck(truckIds);
            for (Object[] row : rows) {
                UUID truckId = (UUID) row[0];
                // Only keep the first result per truck (query ordered by uploadedAt ASC)
                coverPhotos.putIfAbsent(truckId, baseUrl + "/api/v1/files/" + row[1]);
            }
        }

        return PagedResponse.<TruckListResponse>builder()
                .content(page.getContent().stream()
                        .map(t -> mapToListResponse(t, coverPhotos.get(t.getId())))
                        .collect(Collectors.toList()))
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /**
     * Build paged response with availability enrichment for public search.
     *
     * After fetching the page, one batch query fetches all CONFIRMED/ACTIVE bookings
     * for the truck IDs on this page. Then each truck is annotated:
     * - APPROVED + no booking → AVAILABLE
     * - APPROVED + booking → RENTED (rentedUntil = booking.endDate)
     * - INACTIVE → UNAVAILABLE (deactivated)
     * - PENDING_APPROVAL → UNAVAILABLE (pending review)
     */
    private PagedResponse<TruckListResponse> buildPagedResponseWithAvailability(Page<Truck> page) {
        List<UUID> truckIds = page.getContent().stream()
                .map(Truck::getId)
                .collect(Collectors.toList());

        // Cover photos — one query
        Map<UUID, String> coverPhotos = new HashMap<>();
        if (!truckIds.isEmpty()) {
            List<Object[]> rows = truckDocumentRepository.findFirstPhotoPerTruck(truckIds);
            for (Object[] row : rows) {
                UUID truckId = (UUID) row[0];
                coverPhotos.putIfAbsent(truckId, baseUrl + "/api/v1/files/" + row[1]);
            }
        }

        // Availability — one extra query for CONFIRMED/ACTIVE bookings on this page
        // truckId → booking (only one booking per truck can be ACTIVE at a time due to conflict check)
        Map<UUID, Booking> activeBookingsByTruckId = new HashMap<>();
        if (!truckIds.isEmpty()) {
            List<Booking> bookings = bookingRepository.findCurrentOrUpcomingBookingsByTruckIds(truckIds);
            for (Booking b : bookings) {
                activeBookingsByTruckId.putIfAbsent(b.getTruck().getId(), b);
            }
        }

        List<TruckListResponse> content = page.getContent().stream()
                .map(truck -> {
                    TruckListResponse response = mapToListResponse(truck, coverPhotos.get(truck.getId()));
                    enrichAvailability(response, truck, activeBookingsByTruckId.get(truck.getId()));
                    return response;
                })
                .collect(Collectors.toList());

        return PagedResponse.<TruckListResponse>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /**
     * Annotate a TruckListResponse with availability status based on the truck's
     * current status and whether it has an active/upcoming booking.
     */
    private void enrichAvailability(TruckListResponse response, Truck truck, Booking activeBooking) {
        switch (truck.getStatus()) {
            case APPROVED -> {
                if (activeBooking == null) {
                    response.setAvailabilityStatus("AVAILABLE");
                } else {
                    response.setAvailabilityStatus("RENTED");
                    response.setRentedUntil(activeBooking.getEndDate().toString());
                }
            }
            case INACTIVE -> {
                response.setAvailabilityStatus("UNAVAILABLE");
                response.setUnavailableReason("Deactivated by owner");
            }
            case PENDING_APPROVAL -> {
                response.setAvailabilityStatus("UNAVAILABLE");
                response.setUnavailableReason("Pending approval");
            }
            default -> {
                // REJECTED trucks should not reach here (filtered in JPQL)
                response.setAvailabilityStatus("UNAVAILABLE");
            }
        }
    }
}
