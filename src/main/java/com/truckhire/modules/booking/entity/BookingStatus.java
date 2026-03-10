package com.truckhire.modules.booking.entity;

/**
 * Booking lifecycle status.
 *
 * PENDING   → Renter requested; awaiting owner action
 * CONFIRMED → Owner accepted
 * REJECTED  → Owner declined
 * CANCELLED → Renter or owner cancelled (only from PENDING or CONFIRMED)
 * ACTIVE    → Truck handed off; odometer_start recorded by owner
 * COMPLETED → Truck returned; odometer_end recorded; final amount calculated
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED,
    ACTIVE,
    COMPLETED
}
