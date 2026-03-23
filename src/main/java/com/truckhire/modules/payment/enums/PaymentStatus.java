package com.truckhire.modules.payment.enums;

public enum PaymentStatus {
    NOT_INITIATED,  // no transaction exists yet (booking is PENDING, renter hasn't paid)
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED,
    PAYOUT_PENDING,
    PAID_OUT
}
