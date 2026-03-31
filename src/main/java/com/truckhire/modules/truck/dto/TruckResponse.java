package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for full truck detail — GET /trucks/{id}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckResponse {

    private String id;
    private String ownerId;
    private String ownerName;

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
    private Double latitude;
    private Double longitude;

    // ── Specs ──
    private Integer capacityTons;
    private String torque;
    private Integer mileageTotal;
    private Integer year;
    private String color;
    private String fuelType;
    private String vinNumber;

    // ── Status ──
    private String status; // "PENDING_APPROVAL", "APPROVED", "REJECTED", "INACTIVE"
    private String rejectionReason;
    private String description;

    // ── Metadata ──
    private String createdAt;

    // ── Availability (enriched at query time) ──
    private String availabilityStatus;   // "AVAILABLE", "RENTED", "UNAVAILABLE"
    private String rentedUntil;          // ISO date, only when RENTED
    private String unavailableReason;    // only when UNAVAILABLE
    private String coverPhotoUrl;
}
