package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One booking's payment summary — top-level item in GET /payments/mine for RENTER role.
 *
 * displayStatus is derived from business outcome, not a raw transaction status:
 *   PENDING    — charge exists but not yet captured
 *   SUCCEEDED  — charge captured, no refund
 *   REFUNDED   — charge was fully refunded
 *   FAILED     — charge failed
 *
 * Frontend applies its existing label mapping to displayStatus:
 *   SUCCEEDED → "Paid", REFUNDED → "Refunded", PENDING → "Pending", FAILED → "Failed"
 *
 * invoices contains the raw transactions for expanded view rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenterPaymentGroupResponse {

    private String bookingId;
    private String bookingNumber;
    private String truckModel;
    private String truckRegistration;
    private String truckCoverPhotoUrl;   // nullable — show placeholder if null
    private String startDate;            // YYYY-MM-DD
    private String endDate;              // YYYY-MM-DD

    private String displayStatus;        // PENDING | SUCCEEDED | REFUNDED | FAILED
    private BigDecimal displayAmount;    // net amount paid for this booking (refunds subtracted)
    private String currency;
    private String gateway;
    private String paidAt;               // ISO timestamp of CHARGE transaction; null if still PENDING

    private String chargeInvoiceId;      // transaction UUID — pass to GET /payments/{id}/download
    private String chargeInvoiceNumber;  // e.g. INV007
    private String mileageInvoiceId;     // nullable — only present when mileage topup occurred
    private String mileageInvoiceNumber; // nullable

    private List<RenterPaymentInvoiceItem> invoices; // all raw transactions for this booking
}
