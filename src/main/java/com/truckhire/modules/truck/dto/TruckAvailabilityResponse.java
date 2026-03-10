package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for GET /trucks/{id}/availability
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TruckAvailabilityResponse {

    private String truckId;
    private boolean available;
    private String startDate;
    private String endDate;
    private ConflictingRange conflictingRange; // null when available

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictingRange {
        private String startDate;
        private String endDate;
    }
}
