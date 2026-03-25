package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for GET /admin/payments/{id} — full invoice detail.
 * Extends the list view with card details, fee breakdown, and nested booking info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminInvoiceDetailResponse {

    private String id;
    private String invoiceNumber;
    private String bookingNumber;
    private String paymentDate;
    private String gateway;
    private String invoiceType;      // "DAY_RATE" or "MILEAGE"
    private String paymentStatus;
    private String settlementStatus;

    // Payment amounts
    private BigDecimal total;        // amount for this specific transaction
    private BigDecimal ownerShare;   // owner_amount (null until payout initiated)
    private BigDecimal platformShare;// platform_fee (null until payout initiated)
    private BigDecimal ownerSharePercent;
    private BigDecimal platformFeePercent;

    // Mileage breakdown (populated only when invoiceType = "MILEAGE")
    private Integer milesDriven;
    private BigDecimal costPerMile;
    private BigDecimal dayAmount;    // original day-rate charge for reference
    private BigDecimal mileageAmount;// miles charge (milesDriven x costPerMile)
    private BigDecimal totalBookingAmount; // dayAmount + mileageAmount = full booking cost

    // Card details — populated from Stripe webhook (null until wired)
    private String transactionId;    // gatewayPaymentId
    private String paymentMethod;    // e.g. "card" — null until populated
    private String cardLast4;        // last 4 digits — null until populated

    // Nested booking info
    private BookingDetail booking;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingDetail {
        private String id;
        private String startDate;
        private String endDate;
        private String pickupLocation;
        private String dropoffLocation;
        private BigDecimal insuranceCost;           // null — future phase
        private BigDecimal additionalServicesCost;  // null — future phase
        private BigDecimal tax;                     // null — future phase

        private RenterInfo renter;
        private OwnerInfo owner;
        private TruckInfo truck;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenterInfo {
        private String fullname;
        private String email;
        private String phone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerInfo {
        private String fullname;
        private String bankName;
        private String accountNumber;   // masked: ****XXXX (last 4 digits only)
        private String routingNumber;   // bank_ifsc_code / routing number
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TruckInfo {
        private String model;
        private BigDecimal pricePerDay;
        private String registrationNumber;
        private String locationCity;
    }
}
