package com.truckhire.modules.booking.dto;

import com.truckhire.modules.addon.dto.BookingAddonSummary;
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
    private String status;        // raw enum: PENDING, AWAITING_APPROVAL, CONFIRMED, ACTIVE, COMPLETED, REJECTED, CANCELLED
    private String displayStatus; // UI label: Upcoming, Ongoing, Overdue, Completed, Rejected, Cancelled
    private String createdAt;

    // Renter info
    private RenterInfo renter;

    // Owner info
    private OwnerInfo ownerInfo;

    // Truck info
    private TruckInfo truck;

    // Dates — full ISO-8601 datetime (e.g. "2026-05-10T00:00" or "2026-05-10T14:00")
    private String startDate;
    private String endDate;
    // Date-only strings for UI display (e.g. "2026-05-10") — stable across time-of-day changes
    private String startDateFormatted;
    private String endDateFormatted;
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
    private BigDecimal mileageAmount;           // null until return
    private BigDecimal totalAmount;             // null until COMPLETED
    private BigDecimal insuranceCost;           // null until future phase
    private BigDecimal additionalServicesCost;  // null until future phase
    private BigDecimal tax;                     // null until future phase

    // Derived flag — true when status=ACTIVE and endDate < today
    private Boolean isOverdue;

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

    // Addon services selected at booking time
    private List<BookingAddonSummary> addons;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenterInfo {
        private String id;
        private String fullname;
        private String phone;
        private String email;
        private String dob;
        private String address;
        private String city;
        private String state;
        private String country;
        private String zipcode;
        private String status;
        private Boolean kycVerified;
        private String profileImageUrl;
        private List<KycDocument> kycDocuments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerInfo {
        private String id;
        private String fullname;
        private String phone;
        private String email;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KycDocument {
        private String id;
        private String documentType;   // DRIVER_LICENSE | PASSPORT | STATE_ID
        private String fileUrl;
        private String status;         // PENDING | VERIFIED | REJECTED
        private String rejectionReason;
        private String uploadedAt;
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
        private Integer capacityTons;
        private Integer year;
        private String color;
        private String fuelType;    // PETROL / DIESEL / ELECTRIC / HYBRID — null for existing trucks
        private String vinNumber;
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
