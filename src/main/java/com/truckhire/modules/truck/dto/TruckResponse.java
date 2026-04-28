package com.truckhire.modules.truck.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for full truck detail — GET /trucks/{id}
 * Always includes null fields so frontend can display a dash for missing optional values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TruckResponse {

    private String id;
    private String ownerId;
    private String ownerName;
    private String ownerPhone;
    private String ownerEmail;

    // ── Classification ──
    private String vehicleType;
    private String registrationNumber;
    private String model;
    private String make;

    // ── Pricing ──
    private BigDecimal pricePerDay;
    private BigDecimal costPerMile;

    // ── Location ──
    private String locationCity;
    private String locationState;
    private Double latitude;
    private Double longitude;

    // ── Specs ──
    private Integer capacityTons;
    private String engine;
    private String torque;
    private String towingCapacity;
    private Integer mileageTotal;
    private Integer year;
    private String vinNumber;
    private String color;
    private String fuelType;
    private String transmission;

    // ── Status ──
    private String status; // "AVAILABLE", "UNAVAILABLE"
    private String description;

    /** True when the owner has attached an active insurance plan to this truck */
    private boolean insured;

    // ── Pickup Locations ──
    private List<String> pickupLocations;  // multiple pickup cities (SCRUM-87/88); empty if none set

    // ── Metadata ──
    private String createdAt;
    private String updatedAt;

    // ── Availability (enriched at query time) ──
    private String availabilityStatus;   // "AVAILABLE", "RENTED", "UNAVAILABLE"
    private String rentedUntil;          // ISO date, only when RENTED
    private String unavailableReason;    // only when UNAVAILABLE
    private String coverPhotoUrl;
    private List<String> photoUrls;      // all uploaded photos, cover photo first

    // ── Active rental summary (populated only when availabilityStatus = "RENTED") ──
    private RentalInfo rentalInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RentalInfo {
        private String renterName;
        private String bookingNumber;
        private String startDate;   // YYYY-MM-DD
        private String endDate;     // YYYY-MM-DD
    }
}
