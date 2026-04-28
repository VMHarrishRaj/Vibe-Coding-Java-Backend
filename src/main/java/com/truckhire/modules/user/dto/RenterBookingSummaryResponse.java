package com.truckhire.modules.user.dto;

import com.truckhire.modules.booking.dto.BookingListResponse;
import com.truckhire.common.dto.PagedResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response for GET /admin/users/{userId}/bookings.
 * Combines booking statistics with a paginated booking list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenterBookingSummaryResponse {

    private Stats stats;
    private PagedResponse<BookingListResponse> bookings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {
        private long totalBookings;
        private BigDecimal totalSpent;      // sum of totalAmount for COMPLETED bookings
        private BigDecimal avgBookingValue; // totalSpent / completed count, null if no completed bookings
        private String lastBookingDate;     // createdAt of the most recent booking, null if none
    }
}
