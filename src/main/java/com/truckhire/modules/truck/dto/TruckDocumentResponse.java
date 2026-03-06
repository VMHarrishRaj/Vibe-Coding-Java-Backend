package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for truck documents/photos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckDocumentResponse {

    private String id;
    private String documentType; // "PHOTO", "RC", "INSURANCE", "PERMIT"
    private String filePath;
    private String fileUrl;
    private String uploadedAt;
}
