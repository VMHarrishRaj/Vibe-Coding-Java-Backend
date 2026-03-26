package com.truckhire.modules.payment.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.payment.enums.PaymentGateway;
import com.truckhire.modules.payment.enums.PaymentStatus;
import com.truckhire.modules.payment.enums.PaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PaymentTransaction Entity — maps to 'payment_transactions' table.
 *
 * One row per payment event: CHARGE when renter pays, REFUND when booking is
 * cancelled after payment, PAYOUT when owner is paid out after COMPLETED.
 *
 * WHY SEPARATE ROWS PER TYPE (not updating a single row)?
 * Audit trail: you can see every financial event independently.
 * Idempotency: you can check "is there already a SUCCEEDED CHARGE for this booking?"
 * without ambiguity.
 *
 * currency is stored per-transaction — immutable after creation.
 * Admin can switch currency in platform_settings, but it only affects NEW transactions.
 */
@Entity
@Table(name = "payment_transactions",
    indexes = {
        @Index(name = "idx_payment_txn_booking", columnList = "booking_id"),
        @Index(name = "idx_payment_txn_order",   columnList = "gateway_order_id"),
        @Index(name = "idx_payment_txn_status",  columnList = "status")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private PaymentGateway gateway;

    // Gateway-assigned order/payment-intent ID (created before the user pays)
    @Column(name = "gateway_order_id", length = 255)
    private String gatewayOrderId;

    // Gateway-assigned payment ID (set after successful payment)
    @Column(name = "gateway_payment_id", length = 255)
    private String gatewayPaymentId;

    // HMAC signature from Razorpay (stored post-verify for audit)
    @Column(name = "gateway_signature", length = 512)
    private String gatewaySignature;

    // Payout/transfer ID from gateway
    @Column(name = "gateway_transfer_id", length = 255)
    private String gatewayTransferId;

    // Amount in currency units (not paise/cents) — human-readable
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "platform_fee", precision = 12, scale = 2)
    private BigDecimal platformFee;

    @Column(name = "owner_amount", precision = 12, scale = 2)
    private BigDecimal ownerAmount;

    // Currency code at time of transaction — immutable; always set explicitly from platform_settings
    @Column(length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private PaymentType type;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    // Sequential invoice number — INV001, INV002 — generated at transaction creation
    @Column(name = "invoice_number", length = 20, unique = true)
    private String invoiceNumber;

    // Card details — populated from Stripe webhook payload
    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;
}
