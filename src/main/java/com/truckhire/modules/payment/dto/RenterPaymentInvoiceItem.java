package com.truckhire.modules.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One raw transaction line inside a RenterPaymentGroupResponse.invoices list.
 * Frontend uses this for expanded card views showing individual charge/refund rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenterPaymentInvoiceItem {

    private String id;             // transaction UUID — use for /payments/{id}/download
    private String type;           // CHARGE | MILEAGE_TOPUP | REFUND
    private String label;          // "Day Rate" | "Mileage Charge" | "Refund"
    private BigDecimal amount;
    private String status;         // raw PaymentStatus — apply same label mapping as displayStatus
    private String invoiceNumber;  // null for REFUND rows
    private boolean downloadable;  // true for CHARGE and MILEAGE_TOPUP; false for REFUND
    private String createdAt;
}
