package com.truckhire.modules.truck.dto;

import com.truckhire.modules.truck.entity.FuelType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for adding a new truck — POST /trucks
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTruckRequest {

    @NotBlank(message = "Vehicle type is required")
    private String vehicleType; // "MINI", "STANDARD", "HEAVY"

    @NotBlank(message = "Registration number is required")
    @Size(max = 50, message = "Registration number must not exceed 50 characters")
    private String registrationNumber;

    @NotBlank(message = "Model is required")
    @Size(max = 255, message = "Model must not exceed 255 characters")
    private String model;

    @NotBlank(message = "Make is required")
    @Size(max = 255, message = "Make must not exceed 255 characters")
    private String make;

    @NotNull(message = "Price per day is required")
    @DecimalMin(value = "0.01", message = "Price per day must be greater than 0")
    private BigDecimal pricePerDay;

    @NotNull(message = "Cost per mile is required")
    @DecimalMin(value = "0.0", message = "Cost per mile cannot be negative")
    private BigDecimal costPerMile;

    @NotBlank(message = "Location city is required")
    @Size(max = 255, message = "Location city must not exceed 255 characters")
    private String locationCity;

    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private Double longitude;

    @Min(value = 1, message = "Capacity must be at least 1 ton")
    private Integer capacityTons;
    private String torque;
    private String description;

    // ── Extended fields (optional) ──
    private Integer year;
    private String color;
    private FuelType fuelType;

    @Size(max = 17, message = "VIN number must not exceed 17 characters")
    private String vinNumber;
}
