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
import com.truckhire.modules.payment.repository.PaymentTransactionRepository;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Truck Service — business logic for truck CRUD, photo uploads, and availability.
 *
 * CRITICAL BUSINESS RULES:
 * 1. Only KYC-verified owners can add/manage trucks
 * 2. New trucks start as AVAILABLE immediately
 * 3. Owner can toggle truck to UNAVAILABLE (maintenance) via deactivate endpoint
 * 4. Admin suspending an owner sets their AVAILABLE trucks to UNAVAILABLE (suspendedByAdmin=true)
 * 5. Admin re-activating owner restores those trucks to AVAILABLE
 * 6. Only the truck owner can edit/delete their own trucks
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
    private final PaymentTransactionRepository transactionRepository;
    private final PickupLocationRepository pickupLocationRepository;
    private final TruckBlockedDateRepository blockedDateRepository;

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
                .locationState(request.getLocationState() != null ? request.getLocationState().trim() : null)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .capacityTons(request.getCapacityTons())
                .engine(request.getEngine())
                .torque(request.getTorque())
                .towingCapacity(request.getTowingCapacity())
                .description(request.getDescription())
                .year(request.getYear())
                .color(request.getColor())
                .fuelType(request.getFuelType())
                .vinNumber(request.getVinNumber())
                .transmission(request.getTransmission())
                .status(TruckStatus.AVAILABLE)
                .build();

        // Add pickup locations if provided
        if (request.getPickupLocations() != null && !request.getPickupLocations().isEmpty()) {
            request.getPickupLocations().stream()
                    .filter(city -> city != null && !city.isBlank())
                    .map(city -> PickupLocation.builder().truck(truck).city(city.trim()).build())
                    .forEach(loc -> truck.getPickupLocations().add(loc));
        }

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

        if (request.getVehicleType() != null) {
            VehicleType vehicleType = vehicleTypeRepository
                    .findByName(request.getVehicleType().toUpperCase())
                    .orElseThrow(() -> new BusinessException("INVALID_VEHICLE_TYPE",
                            "Invalid vehicle type: " + request.getVehicleType()));
            truck.setVehicleType(vehicleType);
        }
        if (request.getRegistrationNumber() != null) {
            String newRegNum = request.getRegistrationNumber().toUpperCase().trim();
            if (!newRegNum.equals(truck.getRegistrationNumber()) &&
                    truckRepository.existsByRegistrationNumberAndDeletedAtIsNull(newRegNum)) {
                throw new BusinessException("REGISTRATION_NUMBER_TAKEN",
                        "A truck with this registration number already exists");
            }
            truck.setRegistrationNumber(newRegNum);
        }
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
        if (request.getLocationState() != null)
            truck.setLocationState(request.getLocationState().trim());
        if (request.getLatitude() != null)
            truck.setLatitude(request.getLatitude());
        if (request.getLongitude() != null)
            truck.setLongitude(request.getLongitude());
        if (request.getCapacityTons() != null)
            truck.setCapacityTons(request.getCapacityTons());
        if (request.getEngine() != null)
            truck.setEngine(request.getEngine());
        if (request.getTorque() != null)
            truck.setTorque(request.getTorque());
        if (request.getTowingCapacity() != null)
            truck.setTowingCapacity(request.getTowingCapacity());
        if (request.getDescription() != null)
            truck.setDescription(request.getDescription());
        if (request.getYear() != null)
            truck.setYear(request.getYear());
        if (request.getColor() != null)
            truck.setColor(request.getColor());
        if (request.getFuelType() != null)
            truck.setFuelType(request.getFuelType());
        if (request.getVinNumber() != null)
            truck.setVinNumber(request.getVinNumber());
        if (request.getTransmission() != null)
            truck.setTransmission(request.getTransmission());

        // Pickup locations: null = no change; empty list = remove all; non-empty = replace all
        if (request.getPickupLocations() != null) {
            List<String> newCities = request.getPickupLocations().stream()
                    .filter(city -> city != null && !city.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            
            // Remove cities not in the new list
            truck.getPickupLocations().removeIf(loc -> !newCities.contains(loc.getCity()));
            
            // Add new cities not in the current list
            List<String> existingCities = truck.getPickupLocations().stream()
                    .map(PickupLocation::getCity)
                    .toList();
            
            newCities.stream()
                    .filter(city -> !existingCities.contains(city))
                    .map(city -> PickupLocation.builder().truck(truck).city(city).build())
                    .forEach(loc -> truck.getPickupLocations().add(loc));
        }

        Truck saved = truckRepository.save(truck);
        log.info("Truck updated: id={}", truckId);
        return mapToResponse(saved);
    }

    /**
     * Set a truck to UNAVAILABLE (maintenance). Only AVAILABLE trucks can be deactivated.
     */
    @Transactional
    public void deactivateTruck(UUID ownerId, UUID truckId) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        if (truck.getStatus() != TruckStatus.AVAILABLE) {
            throw new BusinessException("TRUCK_NOT_AVAILABLE",
                    "Only available trucks can be set to maintenance");
        }

        truck.setStatus(TruckStatus.UNAVAILABLE);
        truckRepository.save(truck);
        log.info("Truck set to maintenance: id={}, owner={}", truckId, ownerId);
    }

    /**
     * Bring a truck back from maintenance (UNAVAILABLE → AVAILABLE).
     * Only trucks the owner manually deactivated can be reactivated this way.
     * Trucks set UNAVAILABLE by an admin suspension (suspendedByAdmin=true) can only
     * be restored by the admin re-activating the owner account.
     */
    @Transactional
    public void reactivateTruck(UUID ownerId, UUID truckId) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        if (truck.getStatus() != TruckStatus.UNAVAILABLE) {
            throw new BusinessException("TRUCK_NOT_UNAVAILABLE",
                    "Truck is already available");
        }

        if (truck.isSuspendedByAdmin()) {
            throw new BusinessException("TRUCK_SUSPENDED_BY_ADMIN",
                    "This truck was suspended by an admin. Contact support to reactivate your account.");
        }

        truck.setStatus(TruckStatus.AVAILABLE);
        truckRepository.save(truck);
        log.info("Truck reactivated from maintenance: id={}, owner={}", truckId, ownerId);
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
     */
    @Transactional
    public TruckDocumentResponse uploadTruckPhoto(UUID ownerId, UUID truckId,
            String documentType, MultipartFile file) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        String docTypeName = (documentType != null) ? documentType.toUpperCase() : "PHOTO";

        DocumentType docType = documentTypeRepository
                .findByNameAndCategory(docTypeName, "VEHICLE")
                .orElseThrow(() -> new BusinessException("INVALID_DOCUMENT_TYPE",
                        "Invalid vehicle document type: " + docTypeName));

        String subDirectory = "trucks/" + truckId;
        String filePath = fileStorageService.storeFile(file, subDirectory);

        TruckDocument document = TruckDocument.builder()
                .truck(truck)
                .documentType(docType)
                .filePath(filePath)
                .build();

        TruckDocument saved = truckDocumentRepository.save(document);

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
        return buildPagedResponseWithAvailability(page);
    }

    /**
     * Get owner dashboard summary.
     *
     * Returns:
     * - Owner profile (name, email, phone, profileImageUrl, stripeConnected)
     * - Truck counts per status
     * - Booking counts per stage (AWAITING_APPROVAL, CONFIRMED, ACTIVE, COMPLETED) + total
     * - Earnings: total lifetime earnings and pending payout amount
     * - Monthly revenue for the last 12 months (YYYY-MM → ownerAmount sum)
     *   Months with no activity are omitted — frontend fills gaps as "No data available"
     */
    @Transactional(readOnly = true)
    public OwnerDashboardResponse getDashboard(UUID ownerId) {
        // ── Owner profile ──
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ownerId));

        // ── Truck counts ──
        List<Object[]> truckRows = truckRepository.countTrucksByStatusForOwner(ownerId);
        long available = 0, unavailable = 0;
        for (Object[] row : truckRows) {
            TruckStatus status = (TruckStatus) row[0];
            long count = (long) row[1];
            switch (status) {
                case AVAILABLE   -> available = count;
                case UNAVAILABLE -> unavailable = count;
            }
        }

        // ── Booking counts per stage (one batch query) ──
        List<BookingStatus> trackedStatuses = List.of(
                BookingStatus.AWAITING_APPROVAL,
                BookingStatus.CONFIRMED,
                BookingStatus.ACTIVE,
                BookingStatus.COMPLETED
        );
        List<Object[]> bookingRows = bookingRepository.countByOwnerIdAndStatuses(ownerId, trackedStatuses);
        long awaitingApproval = 0, confirmed = 0, active = 0, completed = 0;
        for (Object[] row : bookingRows) {
            BookingStatus status = (BookingStatus) row[0];
            long count = (long) row[1];
            switch (status) {
                case AWAITING_APPROVAL -> awaitingApproval = count;
                case CONFIRMED         -> confirmed = count;
                case ACTIVE            -> active = count;
                case COMPLETED         -> completed = count;
                default -> { /* ignored */ }
            }
        }
        long totalBookings = awaitingApproval + confirmed + active + completed;

        // ── Earnings ──
        BigDecimal totalEarnings = transactionRepository.sumOwnerEarnings(ownerId);
        BigDecimal completedPayoutAmount = transactionRepository.sumOwnerCompletedPayouts(ownerId);
        BigDecimal pendingPayoutAmount = transactionRepository.sumOwnerPendingPayouts(ownerId);

        // ── Monthly revenue — last 12 months ──
        List<Object[]> revenueRows = transactionRepository.sumOwnerRevenueGroupedByMonth(ownerId);
        List<OwnerDashboardResponse.MonthlyRevenue> monthlyRevenue = revenueRows.stream()
                .map(row -> OwnerDashboardResponse.MonthlyRevenue.builder()
                        .month((String) row[0])
                        .revenue(new BigDecimal(row[1].toString()))
                        .build())
                .collect(Collectors.toList());

        // ── Fleet availability this month ──
        // For each of the owner's non-deleted trucks, compute what % of days in the
        // current calendar month are available (not occupied by a booking or blocked date).
        // Two batch queries — no N+1 regardless of fleet size.
        List<com.truckhire.modules.truck.entity.Truck> ownerTrucks =
                truckRepository.findByOwnerIdAndDeletedAtIsNull(ownerId);

        List<OwnerDashboardResponse.TruckAvailability> fleetAvailability = List.of();
        if (!ownerTrucks.isEmpty()) {
            YearMonth currentMonth = YearMonth.now();
            LocalDate monthStart = currentMonth.atDay(1);
            LocalDate monthEnd = currentMonth.atEndOfMonth();
            int totalDays = currentMonth.lengthOfMonth();

            List<UUID> truckIds = ownerTrucks.stream().map(com.truckhire.modules.truck.entity.Truck::getId).toList();

            // Batch 1: all bookings overlapping this month for the owner's trucks
            List<Booking> monthBookings = bookingRepository.findBookingsForTrucksInMonth(
                    truckIds, monthStart.atStartOfDay(), monthEnd.atTime(23, 59, 59));

            // Group booking date ranges by truck ID
            Map<UUID, List<Booking>> bookingsByTruck = monthBookings.stream()
                    .collect(Collectors.groupingBy(b -> b.getTruck().getId()));

            // Batch 2: all owner-blocked dates in this month for the owner's trucks
            List<Object[]> blockedRows = blockedDateRepository.findBlockedDatesByTruckIds(
                    truckIds, monthStart, monthEnd);

            Map<UUID, Set<LocalDate>> blockedByTruck = new HashMap<>();
            for (Object[] row : blockedRows) {
                UUID tid = (UUID) row[0];
                LocalDate d = (LocalDate) row[1];
                blockedByTruck.computeIfAbsent(tid, k -> new HashSet<>()).add(d);
            }

            fleetAvailability = ownerTrucks.stream()
                    .map(truck -> {
                        // Collect every day occupied by a booking (clipped to month boundaries)
                        Set<LocalDate> occupiedDays = new HashSet<>();
                        for (Booking b : bookingsByTruck.getOrDefault(truck.getId(), List.of())) {
                            LocalDate bStart = b.getStartDate().toLocalDate().isBefore(monthStart)
                                    ? monthStart : b.getStartDate().toLocalDate();
                            LocalDate bEnd = b.getEndDate().toLocalDate().isAfter(monthEnd)
                                    ? monthEnd : b.getEndDate().toLocalDate();
                            LocalDate d = bStart;
                            while (!d.isAfter(bEnd)) {
                                occupiedDays.add(d);
                                d = d.plusDays(1);
                            }
                        }
                        // Add owner-blocked dates (de-duplicated by the Set)
                        occupiedDays.addAll(blockedByTruck.getOrDefault(truck.getId(), Set.of()));

                        int occupied = occupiedDays.size();
                        int availableDays = totalDays - occupied;
                        int pct = (int) Math.round((availableDays * 100.0) / totalDays);

                        return OwnerDashboardResponse.TruckAvailability.builder()
                                .truckId(truck.getId().toString())
                                .truckName(
                                        (truck.getMake() != null ? truck.getMake() + " " : "")
                                        + truck.getModel())
                                .registrationNumber(truck.getRegistrationNumber())
                                .totalDaysInMonth(totalDays)
                                .occupiedDays(occupied)
                                .availableDays(availableDays)
                                .availabilityPercent(pct)
                                .build();
                    })
                    .sorted(Comparator.comparing(OwnerDashboardResponse.TruckAvailability::getTruckName))
                    .collect(Collectors.toList());
        }

        return OwnerDashboardResponse.builder()
                // profile
                .ownerId(owner.getId().toString())
                .fullname(owner.getFullname())
                .email(owner.getEmail())
                .phone(owner.getPhone())
                .profileImageUrl(owner.getProfileImageUrl())
                .stripeConnected(owner.getStripeAccountId() != null && !owner.getStripeAccountId().isBlank())
                // trucks
                .totalTrucks(available + unavailable)
                .availableTrucks(available)
                .unavailableTrucks(unavailable)
                // bookings
                .awaitingApprovalBookings(awaitingApproval)
                .confirmedBookings(confirmed)
                .activeBookings(active)
                .completedBookings(completed)
                .totalBookings(totalBookings)
                // earnings
                .totalEarnings(totalEarnings)
                .completedPayoutAmount(completedPayoutAmount)
                .pendingPayoutAmount(pendingPayoutAmount)
                // monthly revenue
                .monthlyRevenue(monthlyRevenue)
                // fleet availability this month
                .fleetAvailability(fleetAvailability)
                .build();
    }

    // ═══════════════════════════════════════
    // PUBLIC OPERATIONS (Renter search)
    // ═══════════════════════════════════════

    /**
     * Search/browse trucks (public).
     *
     * Returns AVAILABLE and UNAVAILABLE trucks.
     * After fetching the page, availability is enriched in one extra batch query:
     * - AVAILABLE + no active/upcoming booking → "AVAILABLE"
     * - AVAILABLE + CONFIRMED/ACTIVE booking today or future → "RENTED" + rentedUntil
     * - UNAVAILABLE → "UNAVAILABLE" (maintenance or owner suspended)
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
            LocalDateTime availableFrom, LocalDateTime availableTo,
            Boolean insured,
            Pageable pageable) {

        // ── Date range validation ──
        // Both must be provided together or both omitted.
        if ((availableFrom == null) != (availableTo == null)) {
            throw new BusinessException("INVALID_DATE_RANGE",
                    "Both availableFrom and availableTo must be provided together");
        }
        if (availableFrom != null) {
            if (availableFrom.isBefore(LocalDateTime.now())) {
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
                    insured, availableFrom, availableTo,
                    availableFrom.toLocalDate(), availableTo.toLocalDate(), sortedPageable);
        } else {
            page = truckRepository.searchPublicTrucks(
                    cityLower, vehicleTypeNorm, minPrice, maxPrice, minCapacity,
                    insured, sortedPageable);
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

        // All photos — oldest first so cover photo (first upload) is index 0
        List<String> photoUrls = truckDocumentRepository.findAllPhotosByTruckId(truckId).stream()
                .map(doc -> baseUrl + "/api/v1/files/" + doc.getFilePath())
                .collect(Collectors.toList());
        String coverPhotoUrl = photoUrls.isEmpty() ? null : photoUrls.get(0);

        // Availability — reuse the same CONFIRMED/ACTIVE batch query with a single-element list
        List<Booking> bookings = bookingRepository.findCurrentOrUpcomingBookingsByTruckIds(List.of(truckId));
        Booking activeBooking = bookings.isEmpty() ? null : bookings.get(0);

        return mapToDetailResponse(truck, coverPhotoUrl, photoUrls, activeBooking);
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

        // Include owner-blocked dates from today forward so renter date picker grays them out
        List<String> blockedDates = blockedDateRepository
                .findBlockedDatesBetween(truckId, LocalDate.now(), LocalDate.now().plusYears(1))
                .stream()
                .map(LocalDate::toString)
                .toList();

        return BookedDatesResponse.builder()
                .truckId(truckId.toString())
                .bookedRanges(ranges)
                .blockedDates(blockedDates)
                .build();
    }

    /**
     * Owner: get the full calendar view for a truck for a given year+month.
     * Returns one entry per day in the month with color and type.
     * - BOOKED = has a booking (non-interactive for owner)
     * - BLOCKED_BY_OWNER = owner has manually blocked (interactive, owner can unblock)
     * - AVAILABLE = neither booked nor blocked (interactive, owner can block)
     */
    @Transactional(readOnly = true)
    public List<CalendarDayResponse> getCalendar(UUID ownerId, UUID truckId, int year, int month) {
        findTruckOwnedBy(truckId, ownerId);

        LocalDate firstDay = LocalDate.of(year, month, 1);
        LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

        // Fetch booked date ranges for this month
        List<Booking> bookings = bookingRepository.findUpcomingBookingsByTruckId(truckId);
        // Build set of all booked dates in the requested month
        java.util.Set<LocalDate> bookedDates = new java.util.HashSet<>();
        for (Booking b : bookings) {
            LocalDate start = b.getStartDate().toLocalDate().isBefore(firstDay) ? firstDay : b.getStartDate().toLocalDate();
            LocalDate end = b.getEndDate().toLocalDate().isAfter(lastDay) ? lastDay : b.getEndDate().toLocalDate();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                bookedDates.add(d);
            }
        }

        // Fetch owner-blocked dates for this month
        java.util.Set<LocalDate> ownerBlocked = new java.util.HashSet<>(
                blockedDateRepository.findBlockedDatesBetween(truckId, firstDay, lastDay));

        List<CalendarDayResponse> result = new java.util.ArrayList<>();
        for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
            if (bookedDates.contains(d)) {
                result.add(CalendarDayResponse.builder()
                        .date(d.toString()).color("red").type("BOOKED").interactive(false).build());
            } else if (ownerBlocked.contains(d)) {
                result.add(CalendarDayResponse.builder()
                        .date(d.toString()).color("red").type("BLOCKED_BY_OWNER").interactive(true).build());
            } else {
                result.add(CalendarDayResponse.builder()
                        .date(d.toString()).color("green").type("AVAILABLE").interactive(true).build());
            }
        }
        return result;
    }

    /**
     * Owner: toggle a single date — block it if available, unblock it if owner-blocked.
     * Guards:
     * - Past dates are rejected (cannot retroactively change history)
     * - Dates with an active booking cannot be blocked/unblocked
     */
    @Transactional
    public ToggleBlockedDateResponse toggleBlockedDate(UUID ownerId, UUID truckId, LocalDate date) {
        Truck truck = findTruckOwnedBy(truckId, ownerId);

        if (date.isBefore(LocalDate.now())) {
            throw new BusinessException("PAST_DATE", "Cannot modify availability for past dates");
        }

        boolean hasBooking = bookingRepository.existsConflictingBooking(truckId, date.atStartOfDay(), date.atStartOfDay());
        if (hasBooking) {
            throw new BusinessException("DATE_HAS_BOOKING",
                    "This date has an active booking and cannot be blocked or unblocked");
        }

        java.util.Optional<TruckBlockedDate> existing = blockedDateRepository.findByTruckIdAndBlockedDate(truckId, date);
        if (existing.isPresent()) {
            blockedDateRepository.delete(existing.get());
            log.info("Date unblocked: truckId={}, date={}", truckId, date);
            return ToggleBlockedDateResponse.builder()
                    .date(date.toString()).action("UNBLOCKED").color("green").build();
        } else {
            blockedDateRepository.save(TruckBlockedDate.builder().truck(truck).blockedDate(date).build());
            log.info("Date blocked: truckId={}, date={}", truckId, date);
            return ToggleBlockedDateResponse.builder()
                    .date(date.toString()).action("BLOCKED").color("red").build();
        }
    }

    /**
     * Owner: batch update blocked dates — block a list and unblock another list in one transaction.
     * Called on "Save Availability" button. Each date is guarded individually (past / has booking),
     * invalid dates are silently skipped and returned in the skipped[] list rather than failing the
     * whole request — so valid changes are always committed even if some dates can't be changed.
     */
    @Transactional
    public com.truckhire.modules.truck.dto.BatchBlockedDatesResponse batchUpdateBlockedDates(
            UUID ownerId, UUID truckId,
            java.util.List<java.time.LocalDate> datesToBlock,
            java.util.List<java.time.LocalDate> datesToUnblock) {

        Truck truck = findTruckOwnedBy(truckId, ownerId);
        LocalDate today = LocalDate.now();

        java.util.List<String> blocked = new java.util.ArrayList<>();
        java.util.List<String> unblocked = new java.util.ArrayList<>();
        java.util.List<String> skipped = new java.util.ArrayList<>();

        // Process blocks
        for (java.time.LocalDate date : datesToBlock) {
            if (date.isBefore(today)) { skipped.add(date.toString()); continue; }
            if (bookingRepository.existsConflictingBooking(truckId, date.atStartOfDay(), date.atStartOfDay())) {
                skipped.add(date.toString()); continue;
            }
            if (!blockedDateRepository.findByTruckIdAndBlockedDate(truckId, date).isPresent()) {
                blockedDateRepository.save(TruckBlockedDate.builder().truck(truck).blockedDate(date).build());
            }
            blocked.add(date.toString());
        }

        // Process unblocks — bulk delete for efficiency
        java.util.List<java.time.LocalDate> validUnblocks = datesToUnblock.stream()
                .filter(date -> !date.isBefore(today))
                .filter(date -> !bookingRepository.existsConflictingBooking(truckId, date.atStartOfDay(), date.atStartOfDay()))
                .collect(java.util.stream.Collectors.toList());

        datesToUnblock.stream()
                .filter(date -> !validUnblocks.contains(date))
                .forEach(date -> skipped.add(date.toString()));

        if (!validUnblocks.isEmpty()) {
            blockedDateRepository.deleteByTruckIdAndBlockedDateIn(truckId, validUnblocks);
            validUnblocks.forEach(date -> unblocked.add(date.toString()));
        }

        log.info("Batch availability update: truckId={}, blocked={}, unblocked={}, skipped={}",
                truckId, blocked.size(), unblocked.size(), skipped.size());

        return com.truckhire.modules.truck.dto.BatchBlockedDatesResponse.builder()
                .blocked(blocked)
                .unblocked(unblocked)
                .skipped(skipped)
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

        boolean hasConflict = bookingRepository.existsConflictingBooking(truckId, startDate.atStartOfDay(), endDate.atStartOfDay())
                || blockedDateRepository.existsBlockedDateInRange(truckId, startDate, endDate);

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
                .filter(b -> !b.getStartDate().toLocalDate().isAfter(endDate) && !b.getEndDate().toLocalDate().isBefore(startDate))
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
     * Get truck legal documents (RC, INSURANCE, PERMIT) — excludes photos.
     */
    @Transactional(readOnly = true)
    public List<TruckDocumentResponse> getTruckDocuments(UUID truckId) {
        truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        return truckDocumentRepository.findLegalDocsByTruckId(truckId)
                .stream()
                .map(this::mapToDocumentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get truck photos only.
     */
    @Transactional(readOnly = true)
    public List<TruckDocumentResponse> getTruckPhotos(UUID truckId) {
        truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        return truckDocumentRepository.findAllPhotosByTruckId(truckId)
                .stream()
                .map(this::mapToDocumentResponse)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════
    // ADMIN OPERATIONS
    // ═══════════════════════════════════════

    /**
     * Admin: List all trucks, optionally filtered by status, vehicleType, and/or free-text keyword.
     *
     * status accepts both raw enum names (AVAILABLE, UNAVAILABLE) and UI display labels
     * (Available, Rented, Not Available). "Available" and "Rented" both resolve to AVAILABLE
     * at the DB level since RENTED is a computed state from bookings.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getAllTrucks(String status, String vehicleType, String q, Pageable pageable) {
        // Resolve status param to a set of TruckStatus values.
        // Returns null (no filter), a single-element list, or a multi-element list.
        List<TruckStatus> statusFilter = resolveAdminStatusFilter(status);

        String normalizedVehicleType = (vehicleType != null && !vehicleType.isBlank())
                ? vehicleType.toUpperCase().trim()
                : null;

        String keyword = (q != null && !q.isBlank())
                ? "%" + q.toLowerCase().trim() + "%"
                : null;

        boolean hasStatus = statusFilter != null;
        boolean multiStatus = hasStatus && statusFilter.size() > 1;
        TruckStatus singleStatus = (hasStatus && !multiStatus) ? statusFilter.get(0) : null;
        boolean hasVehicleType = normalizedVehicleType != null;
        boolean hasKeyword = keyword != null;

        Page<Truck> page;
        if (!hasStatus && !hasVehicleType && !hasKeyword) {
            page = truckRepository.findAllActiveWithOwner(pageable);
        } else if (hasStatus && !hasVehicleType && !hasKeyword) {
            page = multiStatus
                    ? truckRepository.findByStatusesActiveWithOwner(statusFilter, pageable)
                    : truckRepository.findByStatusActiveWithOwnerNoOrder(singleStatus, pageable);
        } else if (!hasStatus && hasVehicleType && !hasKeyword) {
            page = truckRepository.findByVehicleTypeNameAndDeletedAtIsNull(normalizedVehicleType, pageable);
        } else if (hasStatus && hasVehicleType && !hasKeyword) {
            page = multiStatus
                    ? truckRepository.findByStatusesAndVehicleType(statusFilter, normalizedVehicleType, pageable)
                    : truckRepository.findByStatusAndVehicleType(singleStatus, normalizedVehicleType, pageable);
        } else if (!hasStatus && !hasVehicleType && hasKeyword) {
            page = truckRepository.searchByKeyword(keyword, pageable);
        } else if (hasStatus && !hasVehicleType && hasKeyword) {
            page = multiStatus
                    ? truckRepository.searchByKeywordAndStatuses(keyword, statusFilter, pageable)
                    : truckRepository.searchByKeywordAndStatus(keyword, singleStatus, pageable);
        } else if (!hasStatus && hasVehicleType && hasKeyword) {
            page = truckRepository.searchByKeywordAndVehicleType(keyword, normalizedVehicleType, pageable);
        } else {
            // hasStatus && hasVehicleType && hasKeyword
            page = multiStatus
                    ? truckRepository.searchByKeywordAndStatusesAndVehicleType(keyword, statusFilter, normalizedVehicleType, pageable)
                    : truckRepository.searchByKeywordAndStatusAndVehicleType(keyword, singleStatus, normalizedVehicleType, pageable);
        }
        return buildPagedResponseWithAvailability(page);
    }

    /**
     * Map the admin status filter param to a list of TruckStatus enum values.
     * Accepts raw enum names and UI display labels interchangeably.
     * Returns null when no filter should be applied.
     */
    private List<TruckStatus> resolveAdminStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;

        String normalized = status.trim();

        return switch (normalized.toLowerCase()) {
            case "available"     -> List.of(TruckStatus.AVAILABLE);
            case "rented"        -> List.of(TruckStatus.AVAILABLE);
            case "not available" -> List.of(TruckStatus.UNAVAILABLE);
            default -> {
                try {
                    yield List.of(TruckStatus.valueOf(normalized.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
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
        return mapToDetailResponse(truck, null, List.of(), null);
    }

    /**
     * Build a TruckResponse with optional cover photo URL and availability enrichment.
     */
    private TruckResponse mapToDetailResponse(Truck truck, String coverPhotoUrl, List<String> photoUrls, Booking activeBooking) {
        TruckResponse.TruckResponseBuilder builder = TruckResponse.builder()
                .id(truck.getId().toString())
                .ownerId(truck.getOwner().getId().toString())
                .ownerName(truck.getOwner().getFullname())
                .ownerPhone(truck.getOwner().getPhone())
                .ownerEmail(truck.getOwner().getEmail())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay())
                .costPerMile(truck.getCostPerMile())
                .locationCity(truck.getLocationCity())
                .locationState(truck.getLocationState())
                .latitude(truck.getLatitude())
                .longitude(truck.getLongitude())
                .capacityTons(truck.getCapacityTons())
                .engine(truck.getEngine())
                .torque(truck.getTorque())
                .towingCapacity(truck.getTowingCapacity())
                .mileageTotal(truck.getMileageTotal())
                .year(truck.getYear())
                .vinNumber(truck.getVinNumber())
                .color(truck.getColor())
                .fuelType(truck.getFuelType() != null ? truck.getFuelType().name() : null)
                .transmission(truck.getTransmission())
                .status(truck.getStatus().name())
                .description(truck.getDescription())
                .insured(truck.isInsured())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .updatedAt(truck.getUpdatedAt() != null ? truck.getUpdatedAt().toString() : null)
                .coverPhotoUrl(coverPhotoUrl)
                .photoUrls(photoUrls)
                .pickupLocations(truck.getPickupLocations().stream()
                        .map(PickupLocation::getCity)
                        .collect(Collectors.toList()));

        // Availability enrichment — same logic as enrichAvailability() for TruckListResponse
        switch (truck.getStatus()) {
            case AVAILABLE -> {
                if (activeBooking == null) {
                    builder.availabilityStatus("AVAILABLE");
                } else {
                    builder.availabilityStatus("RENTED");
                    builder.rentedUntil(activeBooking.getEndDate().toString());
                    builder.rentalInfo(TruckResponse.RentalInfo.builder()
                            .renterName(activeBooking.getRenter().getFullname())
                            .bookingNumber(activeBooking.getBookingNumber())
                            .startDate(activeBooking.getStartDate().toString())
                            .endDate(activeBooking.getEndDate().toString())
                            .build());
                }
            }
            case UNAVAILABLE -> {
                builder.availabilityStatus("UNAVAILABLE");
                builder.unavailableReason("Not available");
            }
        }

        return builder.build();
    }

    private TruckListResponse mapToListResponse(Truck truck, String coverPhotoUrl, List<String> pickupLocations) {
        return TruckListResponse.builder()
                .id(truck.getId().toString())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay())
                .costPerMile(truck.getCostPerMile())
                .locationCity(truck.getLocationCity())
                .locationState(truck.getLocationState())
                .latitude(truck.getLatitude())
                .longitude(truck.getLongitude())
                .capacityTons(truck.getCapacityTons())
                .engine(truck.getEngine())
                .torque(truck.getTorque())
                .towingCapacity(truck.getTowingCapacity())
                .description(truck.getDescription())
                .year(truck.getYear())
                .vinNumber(truck.getVinNumber())
                .status(truck.getStatus().name())
                .insured(truck.isInsured())
                .ownerId(truck.getOwner().getId().toString())
                .ownerName(truck.getOwner().getFullname())
                .ownerPhone(truck.getOwner().getPhone())
                .ownerEmail(truck.getOwner().getEmail())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .coverPhotoUrl(coverPhotoUrl)
                .pickupLocations(pickupLocations != null ? pickupLocations : List.of())
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
                coverPhotos.putIfAbsent(truckId, baseUrl + "/api/v1/files/" + row[1]);
            }
        }

        // Build truckId -> pickup cities map in one query (no N+1)
        Map<UUID, List<String>> pickupLocationMap = new HashMap<>();
        if (!truckIds.isEmpty()) {
            pickupLocationRepository.findByTruckIdIn(truckIds).forEach(pl ->
                pickupLocationMap.computeIfAbsent(pl.getTruck().getId(), k -> new java.util.ArrayList<>())
                        .add(pl.getCity()));
        }

        return PagedResponse.<TruckListResponse>builder()
                .content(page.getContent().stream()
                        .map(t -> mapToListResponse(t, coverPhotos.get(t.getId()),
                                pickupLocationMap.getOrDefault(t.getId(), List.of())))
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
     * - AVAILABLE + no booking → Available
     * - AVAILABLE + booking    → Rented (rentedUntil = booking.endDate)
     * - UNAVAILABLE            → Not Available (maintenance or admin-suspended)
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

        // Pickup locations — batch fetch to avoid N+1
        Map<UUID, List<String>> pickupLocationMap = new HashMap<>();
        if (!truckIds.isEmpty()) {
            pickupLocationRepository.findByTruckIdIn(truckIds).forEach(pl ->
                pickupLocationMap.computeIfAbsent(pl.getTruck().getId(), k -> new java.util.ArrayList<>())
                        .add(pl.getCity()));
        }

        List<TruckListResponse> content = page.getContent().stream()
                .map(truck -> {
                    TruckListResponse response = mapToListResponse(truck, coverPhotos.get(truck.getId()),
                            pickupLocationMap.getOrDefault(truck.getId(), List.of()));
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
            case AVAILABLE -> {
                if (activeBooking == null) {
                    response.setAvailabilityStatus("AVAILABLE");
                    response.setDisplayStatus("Available");
                } else {
                    response.setAvailabilityStatus("RENTED");
                    response.setDisplayStatus("Rented");
                    response.setRentedUntil(activeBooking.getEndDate().toString());
                }
            }
            case UNAVAILABLE -> {
                response.setAvailabilityStatus("UNAVAILABLE");
                response.setDisplayStatus("Not Available");
                response.setUnavailableReason("Not available");
            }
        }
    }
}
