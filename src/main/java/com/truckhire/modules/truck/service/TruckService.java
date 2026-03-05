package com.truckhire.modules.truck.service;

import com.truckhire.common.dto.PagedResponse;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.common.storage.FileStorageService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
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

    private final TruckRepository truckRepository;
    private final TruckDocumentRepository truckDocumentRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

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
    public TruckResponse addTruck(UUID ownerId, CreateTruckRequest request) {
        User owner = findOwner(ownerId);
        ensureKycVerified(owner);

        // Check unique registration number
        if (truckRepository.existsByRegistrationNumber(request.getRegistrationNumber())) {
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

    // ═══════════════════════════════════════
    // PUBLIC OPERATIONS (Renter search)
    // ═══════════════════════════════════════

    /**
     * Search/browse trucks visible to renters.
     * Only APPROVED trucks are returned.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> searchTrucks(
            String city, String vehicleType, Pageable pageable) {

        Page<Truck> page;

        if (city != null && !city.isBlank()) {
            page = truckRepository.findByStatusAndLocationCityIgnoreCaseAndDeletedAtIsNull(
                    TruckStatus.APPROVED, city.trim(), pageable);
        } else if (vehicleType != null && !vehicleType.isBlank()) {
            page = truckRepository.findByStatusAndVehicleType_NameAndDeletedAtIsNull(
                    TruckStatus.APPROVED, vehicleType.toUpperCase(), pageable);
        } else {
            page = truckRepository.findByStatusAndDeletedAtIsNull(
                    TruckStatus.APPROVED, pageable);
        }

        return buildPagedResponse(page);
    }

    /**
     * Get truck detail (any user).
     */
    @Transactional(readOnly = true)
    public TruckResponse getTruckById(UUID truckId) {
        Truck truck = truckRepository.findByIdAndDeletedAtIsNull(truckId)
                .orElseThrow(() -> new ResourceNotFoundException("Truck", "id", truckId));
        return mapToResponse(truck);
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
     * Admin: List all trucks (any status).
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getAllTrucks(Pageable pageable) {
        Page<Truck> page = truckRepository.findByDeletedAtIsNull(pageable);
        return buildPagedResponse(page);
    }

    /**
     * Admin: List pending-approval trucks.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TruckListResponse> getPendingTrucks(Pageable pageable) {
        Page<Truck> page = truckRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
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
        return TruckResponse.builder()
                .id(truck.getId().toString())
                .ownerId(truck.getOwner().getId().toString())
                .ownerName(truck.getOwner().getFullname())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay().toString())
                .costPerMile(truck.getCostPerMile() != null ? truck.getCostPerMile().toString() : null)
                .locationCity(truck.getLocationCity())
                .latitude(truck.getLatitude())
                .longitude(truck.getLongitude())
                .capacityTons(truck.getCapacityTons())
                .torque(truck.getTorque())
                .mileageTotal(truck.getMileageTotal())
                .status(truck.getStatus().name())
                .description(truck.getDescription())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .build();
    }

    private TruckListResponse mapToListResponse(Truck truck) {
        return TruckListResponse.builder()
                .id(truck.getId().toString())
                .vehicleType(truck.getVehicleType().getName())
                .registrationNumber(truck.getRegistrationNumber())
                .model(truck.getModel())
                .make(truck.getMake())
                .pricePerDay(truck.getPricePerDay().toString())
                .locationCity(truck.getLocationCity())
                .capacityTons(truck.getCapacityTons())
                .status(truck.getStatus().name())
                .ownerName(truck.getOwner().getFullname())
                .createdAt(truck.getCreatedAt() != null ? truck.getCreatedAt().toString() : null)
                .build();
    }

    private TruckDocumentResponse mapToDocumentResponse(TruckDocument doc) {
        return TruckDocumentResponse.builder()
                .id(doc.getId().toString())
                .documentType(doc.getDocumentType().getName())
                .filePath(doc.getFilePath())
                .uploadedAt(doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null)
                .build();
    }

    private PagedResponse<TruckListResponse> buildPagedResponse(Page<Truck> page) {
        return PagedResponse.<TruckListResponse>builder()
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
