package com.truckhire.modules.truck.dto;

import com.truckhire.modules.truck.entity.FuelType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for updating a truck — PUT /trucks/{id}
 * All fields optional (partial update pattern).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTruckRequest {

    private String vehicleType; // "MINI", "STANDARD", "HEAVY"

    @Size(max = 50, message = "Registration number must not exceed 50 characters")
    private String registrationNumber;

    @Size(max = 255, message = "Model must not exceed 255 characters")
    private String model;

    @Size(max = 255, message = "Make must not exceed 255 characters")
    private String make;

    @DecimalMin(value = "0.01", message = "Price per day must be greater than 0")
    private BigDecimal pricePerDay;

    @DecimalMin(value = "0.0", message = "Cost per mile cannot be negative")
    private BigDecimal costPerMile;

    @Size(max = 255, message = "Location city must not exceed 255 characters")
    private String locationCity;

    @Size(max = 100, message = "Location state must not exceed 100 characters")
    private String locationState;

    private Double latitude;
    private Double longitude;
    private Integer capacityTons;
    private String engine;
    private String torque;
    private String towingCapacity;
    private String description;

    // ── Extended fields (optional) ──
    private Integer year;
    private String color;
    private FuelType fuelType;

    @Size(max = 17, message = "VIN number must not exceed 17 characters")
    private String vinNumber;

    // Multiple pickup cities (SCRUM-87/88).
    // null  = no change to existing pickup locations
    // []    = remove all pickup locations
    // [...] = replace all pickup locations with this list
    private List<String> pickupLocations;
}
