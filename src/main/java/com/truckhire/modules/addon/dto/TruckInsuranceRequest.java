package com.truckhire.modules.addon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckInsuranceRequest {

    @NotNull(message = "Insurance plan ID is required")
    private UUID insurancePlanId;

    private String policyNumber;

    @NotBlank(message = "Effective from date is required (YYYY-MM-DD)")
    private String effectiveFrom;

    /** Optional — null means open-ended coverage */
    private String effectiveTo;
}
