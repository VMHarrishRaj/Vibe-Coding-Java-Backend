package com.truckhire.modules.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Condensed booking DTO for list views (renter, owner, admin).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingListResponse {

    private String id;
    private String bookingNumber;
    private String renterName;
    private String truckModel;      // "{make} {model}"
    private String truckId;
    private String startDate;       // YYYY-MM-DD
    private String endDate;         // YYYY-MM-DD
    private Integer totalDays;
    private BigDecimal dayAmount;   // day-based cost (always available)
    private BigDecimal totalAmount; // null until COMPLETED
    private String status;
    private String createdAt;
}
