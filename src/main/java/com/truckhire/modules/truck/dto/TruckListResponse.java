package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

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
    private BigDecimal costPerMile;
    private String locationCity;
    private String locationState;
    private Double latitude;
    private Double longitude;
    private Integer capacityTons;
    private String status;
    private String description;

    // Specs
    private String engine;
    private String torque;
    private String towingCapacity;
    private Integer year;
    private String vinNumber;

    // Owner
    private String ownerId;
    private String ownerName;
    private String ownerPhone;
    private String ownerEmail;

    private String createdAt;
    private String coverPhotoUrl;
    private List<String> pickupLocations;  // multiple pickup cities (SCRUM-87/88); empty if none set       // nullable — first PHOTO document URL, null if no photos uploaded

    /** True when the owner has attached an active insurance plan */
    private boolean insured;

    // Availability enrichment (Phase 5 — populated in public search)
    private String availabilityStatus;  // "AVAILABLE", "RENTED", "UNAVAILABLE"
    private String rentedUntil;         // ISO date, only when RENTED
    private String unavailableReason;   // only when UNAVAILABLE
}
