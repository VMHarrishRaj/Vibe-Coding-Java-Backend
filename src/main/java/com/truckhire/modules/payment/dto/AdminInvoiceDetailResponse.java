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
    private String paymentStatus;           // raw: SUCCEEDED / FAILED / REFUNDED / PENDING
    private String displayPaymentStatus;    // UI label: "Received" / "Failed" / "Refunded" / "Pending"
    private String settlementStatus;        // raw: SETTLED / PAYOUT_PENDING / PENDING
    private String displaySettlementStatus; // UI label: "Settled" / "Settlement Pending" / "Pending"

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

    // Paired mileage transaction — populated when this is a CHARGE and a MILEAGE_TOPUP exists for the same booking
    private MileageDetail mileageTransaction;

    // Combined summary across CHARGE + MILEAGE (always present when called with a CHARGE id)
    private CombinedSummary combinedSummary;

    /**
     * All invoices for this booking in a consistent shape, most recent first.
     * Use this array to render invoice cards — both DAY_RATE and MILEAGE use the same structure.
     * Size 1 when no mileage; size 2 when mileage exists (mileage first, day-rate second).
     */
    private java.util.List<InvoiceEntry> invoices;

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
        private String email;
        private boolean stripeConnected; // true when owner has completed Stripe Connect onboarding
        private String bankName;
        private String accountNumber;   // masked: ****XXXX (last 4 digits only) — null if no bank account linked
        private String routingNumber;   // null if no bank account linked
        private String settlementId;    // gateway transfer ID from the payout transaction (null until payout done)
        private String settlementDate;  // ISO timestamp of the payout transaction (null until payout done)
        private String settlementMethod;// "STRIPE" or "RAZORPAY" (null until payout done)
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

    /** Paired mileage transaction summary — populated only when this detail is for a CHARGE invoice. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MileageDetail {
        private String id;
        private String invoiceNumber;
        private String paymentDate;
        private String paymentStatus;
        private String displayPaymentStatus;
        private String settlementStatus;
        private String displaySettlementStatus;
        private Integer milesDriven;
        private BigDecimal costPerMile;
        private BigDecimal mileageAmount;
        private BigDecimal ownerShare;
        private BigDecimal platformShare;
    }

    /** Combined cost breakdown across CHARGE + MILEAGE_TOPUP for the booking. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombinedSummary {
        private BigDecimal dayAmount;               // CHARGE transaction amount
        private BigDecimal mileageAmount;           // MILEAGE_TOPUP amount (0 if none)
        private BigDecimal insuranceCost;           // from booking.insuranceCost
        private BigDecimal additionalServicesCost;  // from booking.additionalServicesCost
        private BigDecimal totalPaid;               // sum of all the above
        private BigDecimal ownerShare;              // sum across both transactions
        private BigDecimal platformShare;           // sum across both transactions
    }

    /**
     * Uniform invoice entry — same shape for both DAY_RATE and MILEAGE invoices.
     * Use invoices[] array to render invoice cards on the detail page.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceEntry {
        private String id;              // transaction UUID
        private String invoiceNumber;   // INV001, INV002...
        private String invoiceType;     // "DAY_RATE" or "MILEAGE"
        private String paymentDate;
        private String paymentStatus;
        private String displayPaymentStatus;
        private String settlementStatus;
        private String displaySettlementStatus;
        private BigDecimal amount;      // amount for this specific invoice
        private BigDecimal ownerShare;
        private BigDecimal platformShare;
        private String transactionId;   // gateway payment ID (pi_xxx or pay_xxx)
    }
}
