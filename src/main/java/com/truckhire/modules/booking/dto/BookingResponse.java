package com.truckhire.modules.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full booking detail response — used for booking detail endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponse {

    private String id;
    private String bookingNumber;
    private String status;
    private String createdAt;

    // Renter info
    private RenterInfo renter;

    // Truck info
    private TruckInfo truck;

    // Dates
    private String startDate;
    private String endDate;
    private Integer totalDays;

    // Odometer (null until owner records them)
    private Integer odometerStart;
    private Integer odometerEnd;
    private Integer milesDriven;

    // Locations
    private String pickupLocation;
    private String dropoffLocation;

    // Payment summary
    private BigDecimal pricePerDay;
    private BigDecimal dayAmount;
    private BigDecimal costPerMile;
    private BigDecimal mileageAmount;   // null until return
    private BigDecimal totalAmount;     // null until COMPLETED

    // Timestamps
    private String handedOffAt;
    private String returnedAt;
    private String cancelledAt;

    // Notes
    private String renterNotes;
    private String ownerNotes;
    private String cancellationReason;

    // Status history
    private List<StatusHistoryEntry> statusHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenterInfo {
        private String id;
        private String fullname;
        private String phone;
        private String email;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TruckInfo {
        private String id;
        private String registrationNumber;
        private String model;
        private String make;
        private String vehicleType;
        private String locationCity;
        private String coverPhotoUrl;
        private Integer currentMileage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusHistoryEntry {
        private String status;
        private String changedBy;
        private String notes;
        private String changedAt;
    }
}
