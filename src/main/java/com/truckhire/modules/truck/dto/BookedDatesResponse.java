package com.truckhire.modules.truck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for GET /trucks/{id}/booked-dates
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookedDatesResponse {

    private String truckId;
    private List<BookedRange> bookedRanges;
    private List<String> blockedDates;  // owner-blocked individual dates (YYYY-MM-DD) — for renter date picker

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookedRange {
        private String startDate;
        private String endDate;
        private String status; // "PENDING", "CONFIRMED", "ACTIVE"
    }
}
