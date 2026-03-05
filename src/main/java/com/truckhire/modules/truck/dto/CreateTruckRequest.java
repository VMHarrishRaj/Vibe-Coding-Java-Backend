package com.truckhire.modules.truck.dto;

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

    private BigDecimal costPerMile;

    @NotBlank(message = "Location city is required")
    @Size(max = 255, message = "Location city must not exceed 255 characters")
    private String locationCity;

    private Double latitude;
    private Double longitude;
    private Integer capacityTons;
    private String torque;
    private String description;
}
