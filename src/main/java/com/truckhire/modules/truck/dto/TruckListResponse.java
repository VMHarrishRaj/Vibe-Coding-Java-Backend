package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Condensed truck DTO for list/search views.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckListResponse {

    private String id;
    private String vehicleType;
    private String registrationNumber;
    private String model;
    private String make;
    private BigDecimal pricePerDay;
    private String locationCity;
    private Integer capacityTons;
    private String status;
    private String ownerName;
    private String createdAt;
    private String coverPhotoUrl;       // nullable — first PHOTO document URL, null if no photos uploaded

    // Availability enrichment (Phase 5 — populated in public search)
    private String availabilityStatus;  // "AVAILABLE", "RENTED", "UNAVAILABLE"
    private String rentedUntil;         // ISO date, only when RENTED
    private String unavailableReason;   // only when UNAVAILABLE
}
