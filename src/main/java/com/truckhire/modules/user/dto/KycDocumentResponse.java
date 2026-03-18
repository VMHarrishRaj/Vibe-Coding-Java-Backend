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
    private Integer documentTypeId;           // numeric ID: 1=DRIVER_LICENSE, 2=PASSPORT, 3=STATE_ID
    private String documentType;              // human-readable name
    private String fileName;                  // original filename
    private String filePath;                  // internal relative path (for backend use)
    private String fileUrl;                   // full accessible URL
    private String status;                    // "PENDING", "VERIFIED", "REJECTED"
    private String rejectionReason;           // nullable
    private String createdAt;                 // upload timestamp
    private String reviewedAt;                // nullable — when admin reviewed
}
