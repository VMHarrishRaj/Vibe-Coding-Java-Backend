package com.truckhire.modules.truck.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for updating a truck — PUT /trucks/{id}
 * All fields optional (partial update pattern).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTruckRequest {

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

    private Double latitude;
    private Double longitude;
    private Integer capacityTons;
    private String torque;
    private String description;
}
