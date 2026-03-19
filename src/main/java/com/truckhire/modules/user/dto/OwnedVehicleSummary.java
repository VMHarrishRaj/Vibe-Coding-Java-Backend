package com.truckhire.modules.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnedVehicleSummary {
    private String vehicleId;
    private String registrationNumber;
    private String model;
    private Integer capacityTons;
    private String status;   // PENDING_APPROVAL, APPROVED, REJECTED, INACTIVE
    private Long rentals;    // count of ACTIVE + COMPLETED bookings
}
