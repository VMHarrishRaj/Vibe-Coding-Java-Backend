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
public class AddonServiceTypeResponse {

    private String id;
    private String type;
    private String name;
    private String description;
    private String provider;
    private BigDecimal rate;
    private String rateUnit;
    private String contactPhone;
    private String contactEmail;
    private String availability;
    private BigDecimal maxCoverage;
    private String status;
    private String createdAt;
}
