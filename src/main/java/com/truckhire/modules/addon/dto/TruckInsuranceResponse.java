package com.truckhire.modules.addon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckInsuranceResponse {

    private String id;
    private String truckId;
    private String insurancePlanId;
    private String insurancePlanName;
    private String provider;
    private BigDecimal rate;
    private BigDecimal maxCoverage;
    private String policyNumber;
    private String effectiveFrom;
    private String effectiveTo;
    private String createdAt;
}
