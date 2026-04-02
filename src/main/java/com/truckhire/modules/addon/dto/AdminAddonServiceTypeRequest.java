package com.truckhire.modules.addon.dto;

import com.truckhire.modules.addon.entity.AddonType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAddonServiceTypeRequest {

    @NotNull(message = "Addon type is required")
    private AddonType type;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;
    private String provider;

    @NotNull(message = "Rate is required")
    @Positive(message = "Rate must be positive")
    private BigDecimal rate;

    /** PER_DAY | PER_SERVICE | PER_HOUR — defaults to PER_DAY if null */
    private String rateUnit;

    private String contactPhone;
    private String contactEmail;
    private String availability;
    private BigDecimal maxCoverage;
}
