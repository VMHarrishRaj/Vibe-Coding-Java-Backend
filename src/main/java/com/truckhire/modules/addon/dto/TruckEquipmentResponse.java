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
public class TruckEquipmentResponse {

    private String id;
    private String truckId;
    private String name;
    private Integer quantity;
    private String condition;
    private BigDecimal rate;
    private String status;
    private String createdAt;
}
