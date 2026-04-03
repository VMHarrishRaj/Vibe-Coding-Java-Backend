package com.truckhire.modules.payment.enums;

public enum PaymentStatus {
    NOT_INITIATED,  // no transaction exists yet (booking is PENDING, renter hasn't paid)
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED,
    CANCELLED,      // payment was initiated but booking was cancelled before capture — no money moved
    PAYOUT_PENDING,
    PAID_OUT
}
