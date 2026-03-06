package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for KYC documents.
 * Returned when an owner uploads/views their KYC docs,
 * or when an admin reviews KYC for a user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycDocumentResponse {

    private String id;
    private String documentType; // "AADHAAR", "PAN", "LICENSE"
    private String filePath;
    private String fileUrl;
    private String verificationStatus; // "PENDING", "VERIFIED", "REJECTED"
    private String rejectionReason; // nullable
    private String uploadedAt;
    private String verifiedAt; // nullable
}
