package com.truckhire.modules.addon.service;

import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.addon.dto.*;
import com.truckhire.modules.addon.entity.*;
import com.truckhire.modules.addon.repository.*;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.repository.TruckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddonService {

    private final AddonServiceTypeRepository addonServiceTypeRepo;
    private final TruckEquipmentRepository truckEquipmentRepo;
    private final TruckInsuranceRepository truckInsuranceRepo;
    private final BookingAddonRepository bookingAddonRepo;
    private final TruckRepository truckRepository;

    // ═══════════════════════════════════════
    // ADMIN — Addon Service Type CRUD
    // ═══════════════════════════════════════

    @Transactional
    public AddonServiceTypeResponse createAddonServiceType(AdminAddonServiceTypeRequest request) {
        AddonServiceType entity = AddonServiceType.builder()
                .type(request.getType())
                .name(request.getName())
                .description(request.getDescription())
                .provider(request.getProvider())
                .rate(request.getRate())
                .rateUnit(request.getRateUnit() != null ? request.getRateUnit() : "PER_DAY")
                .contactPhone(request.getContactPhone())
                .contactEmail(request.getContactEmail())
                .availability(request.getAvailability())
                .maxCoverage(request.getMaxCoverage())
                .status("ACTIVE")
                .build();
        AddonServiceType saved = addonServiceTypeRepo.save(entity);
        log.info("Admin created addon service type: id={}, type={}, name={}", saved.getId(), saved.getType(), saved.getName());
        return mapAddonServiceType(saved);
    }

    @Transactional
    public AddonServiceTypeResponse updateAddonServiceType(UUID id, AdminAddonServiceTypeRequest request) {
        AddonServiceType entity = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("AddonServiceType", "id", id));
        entity.setType(request.getType());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setProvider(request.getProvider());
        entity.setRate(request.getRate());
        if (request.getRateUnit() != null) entity.setRateUnit(request.getRateUnit());
        entity.setContactPhone(request.getContactPhone());
        entity.setContactEmail(request.getContactEmail());
        entity.setAvailability(request.getAvailability());
        entity.setMaxCoverage(request.getMaxCoverage());
        return mapAddonServiceType(addonServiceTypeRepo.save(entity));
    }

    @Transactional
    public void deleteAddonServiceType(UUID id) {
        AddonServiceType entity = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("AddonServiceType", "id", id));
        entity.softDelete();
        addonServiceTypeRepo.save(entity);
        log.info("Admin soft-deleted addon service type: id={}", id);
    }

    public AddonServiceTypeResponse getAddonServiceType(UUID id) {
        AddonServiceType entity = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("AddonServiceType", "id", id));
        return mapAddonServiceType(entity);
    }

    @Transactional
    public AddonServiceTypeResponse toggleAddonServiceTypeStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new BusinessException("INVALID_STATUS", "Status must be ACTIVE or INACTIVE");
        }
        AddonServiceType entity = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("AddonServiceType", "id", id));
        entity.setStatus(status);
        log.info("Admin toggled addon service type status: id={}, status={}", id, status);
        return mapAddonServiceType(addonServiceTypeRepo.save(entity));
    }

    public PagedResponse<AddonServiceTypeResponse> listAddonServiceTypes(AddonType typeFilter, Pageable pageable) {
        Page<AddonServiceType> page = typeFilter != null
                ? addonServiceTypeRepo.findByTypeAndDeletedAtIsNull(typeFilter, pageable)
                : addonServiceTypeRepo.findByDeletedAtIsNull(pageable);
        return PagedResponse.<AddonServiceTypeResponse>builder()
                .content(page.getContent().stream().map(this::mapAddonServiceType).collect(Collectors.toList()))
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    // ═══════════════════════════════════════
    // OWNER — Truck Insurance
    // ═══════════════════════════════════════

    @Transactional
    public TruckInsuranceResponse attachInsurance(UUID ownerId, UUID truckId, TruckInsuranceRequest request) {
        Truck truck = loadTruckOwnedBy(truckId, ownerId);

        AddonServiceType plan = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(request.getInsurancePlanId())
                .orElseThrow(() -> new ResourceNotFoundException("InsurancePlan", "id", request.getInsurancePlanId()));

        if (plan.getType() != AddonType.INSURANCE) {
            throw new BusinessException("INVALID_ADDON_TYPE", "The selected plan is not an insurance plan");
        }

        LocalDate effectiveFrom = LocalDate.parse(request.getEffectiveFrom());
        LocalDate effectiveTo = request.getEffectiveTo() != null ? LocalDate.parse(request.getEffectiveTo()) : null;

        TruckInsurance insurance = TruckInsurance.builder()
                .truck(truck)
                .insurancePlan(plan)
                .policyNumber(request.getPolicyNumber())
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .build();

        TruckInsurance saved = truckInsuranceRepo.save(insurance);

        // Mark truck as insured
        truck.setInsured(true);
        truckRepository.save(truck);

        log.info("Owner attached insurance: truck={}, plan={}", truckId, plan.getId());
        return mapTruckInsurance(saved);
    }

    public List<TruckInsuranceResponse> getTruckInsurance(UUID ownerId, UUID truckId) {
        loadTruckOwnedBy(truckId, ownerId); // ownership guard
        return truckInsuranceRepo.findByTruckIdAndDeletedAtIsNull(truckId).stream()
                .map(this::mapTruckInsurance)
                .collect(Collectors.toList());
    }

    @Transactional
    public void detachInsurance(UUID ownerId, UUID truckId, UUID insuranceId) {
        loadTruckOwnedBy(truckId, ownerId);
        TruckInsurance insurance = truckInsuranceRepo.findByIdAndTruckIdAndDeletedAtIsNull(insuranceId, truckId)
                .orElseThrow(() -> new ResourceNotFoundException("TruckInsurance", "id", insuranceId));
        insurance.softDelete();
        truckInsuranceRepo.save(insurance);

        // If no active insurance remains, clear the insured flag
        if (!truckInsuranceRepo.existsByTruckIdAndDeletedAtIsNull(truckId)) {
            Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                    .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));
            truck.setInsured(false);
            truckRepository.save(truck);
        }
        log.info("Owner detached insurance: truck={}, insurance={}", truckId, insuranceId);
    }

    // ═══════════════════════════════════════
    // OWNER — Truck Equipment
    // ═══════════════════════════════════════

    @Transactional
    public TruckEquipmentResponse addEquipment(UUID ownerId, UUID truckId, TruckEquipmentRequest request) {
        Truck truck = loadTruckOwnedBy(truckId, ownerId);
        TruckEquipment equipment = TruckEquipment.builder()
                .truck(truck)
                .name(request.getName())
                .quantity(request.getQuantity())
                .condition(request.getCondition())
                .rate(request.getRate())
                .status("AVAILABLE")
                .build();
        TruckEquipment saved = truckEquipmentRepo.save(equipment);
        log.info("Owner added equipment: truck={}, name={}", truckId, request.getName());
        return mapTruckEquipment(saved);
    }

    @Transactional
    public TruckEquipmentResponse updateEquipment(UUID ownerId, UUID truckId, UUID equipmentId, TruckEquipmentRequest request) {
        loadTruckOwnedBy(truckId, ownerId);
        TruckEquipment equipment = truckEquipmentRepo.findByIdAndTruckIdAndDeletedAtIsNull(equipmentId, truckId)
                .orElseThrow(() -> new ResourceNotFoundException("TruckEquipment", "id", equipmentId));
        equipment.setName(request.getName());
        equipment.setQuantity(request.getQuantity());
        equipment.setCondition(request.getCondition());
        equipment.setRate(request.getRate());
        return mapTruckEquipment(truckEquipmentRepo.save(equipment));
    }

    @Transactional
    public void deleteEquipment(UUID ownerId, UUID truckId, UUID equipmentId) {
        loadTruckOwnedBy(truckId, ownerId);
        TruckEquipment equipment = truckEquipmentRepo.findByIdAndTruckIdAndDeletedAtIsNull(equipmentId, truckId)
                .orElseThrow(() -> new ResourceNotFoundException("TruckEquipment", "id", equipmentId));
        equipment.softDelete();
        truckEquipmentRepo.save(equipment);
        log.info("Owner soft-deleted equipment: id={}", equipmentId);
    }

    public List<TruckEquipmentResponse> listEquipment(UUID ownerId, UUID truckId) {
        loadTruckOwnedBy(truckId, ownerId);
        return truckEquipmentRepo.findByTruckIdAndDeletedAtIsNull(truckId).stream()
                .map(this::mapTruckEquipment)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════
    // PUBLIC — Truck Addons Preview (before booking)
    // ═══════════════════════════════════════

    public TruckAddonsResponse getTruckAddons(UUID truckId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));

        // Insurance: get the first active plan attached to this truck (if any)
        TruckInsuranceResponse insurance = truckInsuranceRepo.findByTruckIdAndDeletedAtIsNull(truckId)
                .stream().findFirst().map(this::mapTruckInsurance).orElse(null);

        // RSA: all ACTIVE providers from admin catalog
        List<AddonServiceTypeResponse> rsaOptions = addonServiceTypeRepo
                .findByTypeAndStatusAndDeletedAtIsNull(AddonType.RSA, "ACTIVE")
                .stream().map(this::mapAddonServiceType).collect(Collectors.toList());

        // Equipment: owner's available equipment for this truck
        List<TruckEquipmentResponse> equipment = truckEquipmentRepo
                .findByTruckIdAndDeletedAtIsNull(truckId)
                .stream()
                .filter(e -> "AVAILABLE".equals(e.getStatus()))
                .map(this::mapTruckEquipment)
                .collect(Collectors.toList());

        return TruckAddonsResponse.builder()
                .insurance(insurance)
                .rsaOptions(rsaOptions)
                .equipment(equipment)
                .build();
    }

    // ═══════════════════════════════════════
    // BOOKING INTEGRATION — called from BookingService
    // ═══════════════════════════════════════

    /**
     * Resolves addon selections at booking creation time.
     * Insurance is auto-applied from the truck's attached plan (if any).
     * RSA and equipment come from renter's explicit selection.
     *
     * Returns an AddonResolutionResult with cost breakdown and the BookingAddon rows to persist.
     * The caller (BookingService) saves the Booking first, then calls this method to persist addons.
     */
    @Transactional
    public AddonResolutionResult resolveBookingAddons(
            Booking booking,
            Truck truck,
            List<UUID> rsaAddonIds,
            List<UUID> equipmentIds) {

        List<BookingAddon> addons = new ArrayList<>();
        BigDecimal insuranceCost = BigDecimal.ZERO;
        BigDecimal additionalServicesCost = BigDecimal.ZERO;

        int totalDays = booking.getTotalDays();

        // ── Auto-apply truck insurance ──────────────────────────────────────
        List<TruckInsurance> insuranceRecords = truckInsuranceRepo.findByTruckIdAndDeletedAtIsNull(truck.getId());
        if (!insuranceRecords.isEmpty()) {
            TruckInsurance ti = insuranceRecords.get(0);
            AddonServiceType plan = ti.getInsurancePlan();
            BigDecimal cost = plan.getRate().multiply(BigDecimal.valueOf(totalDays));
            insuranceCost = cost;
            addons.add(BookingAddon.builder()
                    .booking(booking)
                    .addonServiceType(plan)
                    .addonType(AddonType.INSURANCE)
                    .nameSnapshot(plan.getName())
                    .rateSnapshot(plan.getRate())
                    .quantity(totalDays)
                    .totalCost(cost)
                    .build());
        }

        // ── RSA selections ──────────────────────────────────────────────────
        if (rsaAddonIds != null && !rsaAddonIds.isEmpty()) {
            for (UUID rsaId : rsaAddonIds) {
                AddonServiceType rsa = addonServiceTypeRepo.findByIdAndDeletedAtIsNull(rsaId)
                        .orElseThrow(() -> new ResourceNotFoundException("RSA provider", "id", rsaId));
                if (rsa.getType() != AddonType.RSA) {
                    throw new BusinessException("INVALID_ADDON_TYPE", "Addon " + rsaId + " is not an RSA provider");
                }
                BigDecimal cost = rsa.getRate().multiply(BigDecimal.valueOf(totalDays));
                additionalServicesCost = additionalServicesCost.add(cost);
                addons.add(BookingAddon.builder()
                        .booking(booking)
                        .addonServiceType(rsa)
                        .addonType(AddonType.RSA)
                        .nameSnapshot(rsa.getName())
                        .rateSnapshot(rsa.getRate())
                        .quantity(totalDays)
                        .totalCost(cost)
                        .build());
            }
        }

        // ── Equipment selections ────────────────────────────────────────────
        if (equipmentIds != null && !equipmentIds.isEmpty()) {
            List<TruckEquipment> equipmentItems = truckEquipmentRepo
                    .findByIdInAndTruckIdAndDeletedAtIsNull(equipmentIds, truck.getId());

            // Validate all requested IDs belong to this truck
            if (equipmentItems.size() != equipmentIds.size()) {
                throw new BusinessException("INVALID_EQUIPMENT",
                        "One or more selected equipment items do not belong to this truck or are unavailable");
            }

            for (TruckEquipment eq : equipmentItems) {
                BigDecimal cost = eq.getRate().multiply(BigDecimal.valueOf(totalDays));
                additionalServicesCost = additionalServicesCost.add(cost);
                addons.add(BookingAddon.builder()
                        .booking(booking)
                        .truckEquipment(eq)
                        .addonType(AddonType.EQUIPMENT)
                        .nameSnapshot(eq.getName())
                        .rateSnapshot(eq.getRate())
                        .quantity(totalDays)
                        .totalCost(cost)
                        .build());
            }
        }

        if (!addons.isEmpty()) {
            bookingAddonRepo.saveAll(addons);
        }

        return new AddonResolutionResult(insuranceCost, additionalServicesCost, addons);
    }

    /** Load booking addons for display — includes contact info for RSA providers */
    public List<BookingAddonSummary> getBookingAddons(UUID bookingId) {
        return bookingAddonRepo.findByBookingId(bookingId).stream()
                .map(this::mapBookingAddon)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════

    private Truck loadTruckOwnedBy(UUID truckId, UUID ownerId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));
        if (!truck.getOwner().getId().equals(ownerId)) {
            throw new BusinessException("NOT_TRUCK_OWNER", "You do not own this truck");
        }
        return truck;
    }

    private AddonServiceTypeResponse mapAddonServiceType(AddonServiceType e) {
        return AddonServiceTypeResponse.builder()
                .id(e.getId().toString())
                .type(e.getType().name())
                .name(e.getName())
                .description(e.getDescription())
                .provider(e.getProvider())
                .rate(e.getRate())
                .rateUnit(e.getRateUnit())
                .contactPhone(e.getContactPhone())
                .contactEmail(e.getContactEmail())
                .availability(e.getAvailability())
                .maxCoverage(e.getMaxCoverage())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .build();
    }

    private TruckInsuranceResponse mapTruckInsurance(TruckInsurance e) {
        AddonServiceType plan = e.getInsurancePlan();
        return TruckInsuranceResponse.builder()
                .id(e.getId().toString())
                .truckId(e.getTruck().getId().toString())
                .insurancePlanId(plan.getId().toString())
                .insurancePlanName(plan.getName())
                .provider(plan.getProvider())
                .rate(plan.getRate())
                .maxCoverage(plan.getMaxCoverage())
                .policyNumber(e.getPolicyNumber())
                .effectiveFrom(e.getEffectiveFrom() != null ? e.getEffectiveFrom().toString() : null)
                .effectiveTo(e.getEffectiveTo() != null ? e.getEffectiveTo().toString() : null)
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .build();
    }

    private TruckEquipmentResponse mapTruckEquipment(TruckEquipment e) {
        return TruckEquipmentResponse.builder()
                .id(e.getId().toString())
                .truckId(e.getTruck().getId().toString())
                .name(e.getName())
                .quantity(e.getQuantity())
                .condition(e.getCondition())
                .rate(e.getRate())
                .status(e.getStatus())
                .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                .build();
    }

    private BookingAddonSummary mapBookingAddon(BookingAddon e) {
        BookingAddonSummary.BookingAddonSummaryBuilder builder = BookingAddonSummary.builder()
                .id(e.getId().toString())
                .addonType(e.getAddonType().name())
                .name(e.getNameSnapshot())
                .rateSnapshot(e.getRateSnapshot())
                .quantity(e.getQuantity())
                .totalCost(e.getTotalCost());

        // Populate contact info for RSA addons (renter may need to call for help)
        if (e.getAddonType() == AddonType.RSA && e.getAddonServiceType() != null) {
            AddonServiceType svc = e.getAddonServiceType();
            builder.contactPhone(svc.getContactPhone())
                    .contactEmail(svc.getContactEmail())
                    .availability(svc.getAvailability());
        }

        return builder.build();
    }

    // ═══════════════════════════════════════
    // INNER RECORD — result of resolveBookingAddons
    // ═══════════════════════════════════════

    public record AddonResolutionResult(
            BigDecimal insuranceCost,
            BigDecimal additionalServicesCost,
            List<BookingAddon> addons) {
    }
}
