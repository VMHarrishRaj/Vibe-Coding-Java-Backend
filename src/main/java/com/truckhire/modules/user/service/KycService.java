package com.truckhire.modules.user.service;

import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.common.storage.FileStorageService;
import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.entity.TruckStatus;
import com.truckhire.modules.truck.repository.TruckRepository;
import com.truckhire.modules.user.dto.KycDocumentResponse;
import com.truckhire.modules.user.entity.*;
import com.truckhire.modules.user.repository.DocumentTypeRepository;
import com.truckhire.modules.user.repository.UserDocumentRepository;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * KYC Service — handles KYC document upload, retrieval, and verification.
 *
 * BUSINESS RULES:
 * 1. Only OWNERs can upload KYC documents
 * 2. Document types: AADHAAR, PAN, LICENSE (category = "KYC" in document_types)
 * 3. Files stored on disk via FileStorageService, path saved in DB
 * 4. Admin can verify KYC → sets user.kyc_verified = true
 * 5. KYC verification is a prerequisite for truck uploading (frontend enforces,
 * backend guards in TruckService)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final UserDocumentRepository userDocumentRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final FileStorageService fileStorageService;
    private final TruckRepository truckRepository;

    /**
     * Upload a KYC document for the authenticated owner.
     *
     * @param userId       Owner's user ID (from JWT context)
     * @param documentType Document type name (AADHAAR, PAN, LICENSE)
     * @param file         The uploaded file
     * @return Document response with file path and status
     */
    @Transactional
    public KycDocumentResponse uploadKycDocument(UUID userId, String documentType, MultipartFile file) {
        User user = findUserById(userId);

        // Only owners can upload KYC
        if (!Role.OWNER.equals(user.getRole().getName())) {
            throw new BusinessException("NOT_OWNER",
                    "Only owners can upload KYC documents");
        }

        // Validate document type exists and is KYC category
        DocumentType docType = documentTypeRepository
                .findByNameAndCategory(documentType.toUpperCase(), "KYC")
                .orElseThrow(() -> new BusinessException("INVALID_DOCUMENT_TYPE",
                        "Invalid KYC document type: " + documentType +
                                ". Must be one of: AADHAAR, PAN, LICENSE"));

        // Store file on disk: uploads/kyc/{userId}/{uuid}_filename.jpg
        String subDirectory = "kyc/" + userId;
        String filePath = fileStorageService.storeFile(file, subDirectory);

        // Create DB record
        UserDocument document = UserDocument.builder()
                .user(user)
                .documentType(docType)
                .filePath(filePath)
                .verificationStatus(VerificationStatus.PENDING)
                .build();

        UserDocument saved = userDocumentRepository.save(document);
        log.info("KYC document uploaded: userId={}, type={}, path={}",
                userId, documentType, filePath);

        return mapToResponse(saved);
    }

    /**
     * Get all KYC documents for the authenticated owner.
     */
    @Transactional(readOnly = true)
    public List<KycDocumentResponse> getMyKycDocuments(UUID userId) {
        return userDocumentRepository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Admin: Get all KYC documents for a specific user.
     */
    @Transactional(readOnly = true)
    public List<KycDocumentResponse> getKycDocumentsByUserId(UUID userId) {
        // Verify user exists
        findUserById(userId);
        return userDocumentRepository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Admin: Verify KYC for a user.
     *
     * Sets user.kyc_verified = true and marks all PENDING documents as VERIFIED.
     * This is the gate that allows owners to upload trucks.
     */
    @Transactional
    public void verifyKyc(UUID userId, UUID adminId) {
        User user = findUserById(userId);

        if (user.isKycVerified()) {
            throw new BusinessException("KYC_ALREADY_VERIFIED",
                    "KYC is already verified for this user");
        }

        // Check that the user has at least one KYC document
        List<UserDocument> documents = userDocumentRepository.findByUserId(userId);
        if (documents.isEmpty()) {
            throw new BusinessException("NO_KYC_DOCUMENTS",
                    "User has no KYC documents uploaded. Cannot verify KYC.");
        }

        // Mark all PENDING documents as VERIFIED
        OffsetDateTime now = OffsetDateTime.now();
        for (UserDocument doc : documents) {
            if (doc.getVerificationStatus() == VerificationStatus.PENDING) {
                doc.setVerificationStatus(VerificationStatus.VERIFIED);
                doc.setVerifiedAt(now);
            }
        }
        userDocumentRepository.saveAll(documents);

        // Set KYC verified flag on user
        user.setKycVerified(true);

        // If user was PENDING_VERIFICATION, activate them
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
            log.info("User activated after KYC verification: userId={}", userId);
        }

        userRepository.save(user);
        log.info("KYC verified: userId={}, by adminId={}", userId, adminId);
    }

    /**
     * Admin: Reject KYC for a user.
     *
     * Sets all PENDING documents to REJECTED with the given reason.
     * Clears kyc_verified flag and cascades: owner's APPROVED trucks → INACTIVE.
     */
    @Transactional
    public void rejectKyc(UUID userId, UUID adminId, String reason) {
        User user = findUserById(userId);

        if (user.isKycVerified()) {
            throw new BusinessException("KYC_ALREADY_VERIFIED",
                    "Cannot reject KYC for a user whose KYC is already verified");
        }

        List<UserDocument> documents = userDocumentRepository.findByUserId(userId);
        if (documents.isEmpty()) {
            throw new BusinessException("NO_KYC_DOCUMENTS",
                    "User has no KYC documents to reject");
        }

        // Mark all PENDING documents as REJECTED with reason
        OffsetDateTime now = OffsetDateTime.now();
        for (UserDocument doc : documents) {
            if (doc.getVerificationStatus() == VerificationStatus.PENDING) {
                doc.setVerificationStatus(VerificationStatus.REJECTED);
                doc.setRejectionReason(reason);
                doc.setVerifiedAt(now);
            }
        }
        userDocumentRepository.saveAll(documents);

        // Clear KYC flag
        user.setKycVerified(false);
        userRepository.save(user);

        // Cascade: set owner's APPROVED trucks → INACTIVE
        List<Truck> approvedTrucks = truckRepository.findByOwnerIdAndStatusAndDeletedAtIsNull(
                userId, TruckStatus.APPROVED);
        if (!approvedTrucks.isEmpty()) {
            approvedTrucks.forEach(t -> t.setStatus(TruckStatus.INACTIVE));
            truckRepository.saveAll(approvedTrucks);
            log.info("Cascaded KYC rejection: {} trucks set INACTIVE for userId={}", approvedTrucks.size(), userId);
        }

        log.info("KYC rejected: userId={}, by adminId={}, reason={}", userId, adminId, reason);
    }

    // ── Private helpers ──

    private User findUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (user.isDeleted()) {
            throw new ResourceNotFoundException("User", "id", userId);
        }
        return user;
    }

    private KycDocumentResponse mapToResponse(UserDocument doc) {
        return KycDocumentResponse.builder()
                .id(doc.getId().toString())
                .documentType(doc.getDocumentType().getName())
                .filePath(doc.getFilePath())
                .fileUrl(baseUrl + "/api/v1/files/" + doc.getFilePath())
                .verificationStatus(doc.getVerificationStatus().name())
                .rejectionReason(doc.getRejectionReason())
                .uploadedAt(doc.getUploadedAt() != null ? doc.getUploadedAt().toString() : null)
                .verifiedAt(doc.getVerifiedAt() != null ? doc.getVerifiedAt().toString() : null)
                .build();
    }
}
