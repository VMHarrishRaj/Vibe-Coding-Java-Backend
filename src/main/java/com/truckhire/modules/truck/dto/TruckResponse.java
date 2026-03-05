package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String pricePerDay;
    private String costPerMile;

    // ── Location ──
    private String locationCity;
    private Double latitude;
    private Double longitude;

    // ── Specs ──
    private Integer capacityTons;
    private String torque;
    private Integer mileageTotal;

    // ── Status ──
    private String status; // "PENDING_APPROVAL", "APPROVED", "REJECTED", "INACTIVE"
    private String description;

    // ── Metadata ──
    private String createdAt;
}
