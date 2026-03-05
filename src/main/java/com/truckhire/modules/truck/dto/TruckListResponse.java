package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String pricePerDay;
    private String locationCity;
    private Integer capacityTons;
    private String status;
    private String ownerName;
    private String createdAt;
}
