package com.truckhire.modules.booking.entity;

/**
 * Booking lifecycle status.
 *
 * PENDING            → Renter created the booking request; payment not yet initiated
 * AWAITING_APPROVAL  → Renter paid; waiting for owner to approve or reject
 * CONFIRMED          → Owner approved; rental will proceed
 * REJECTED           → Owner declined after payment; refund issued to renter
 * CANCELLED          → Cancelled by renter (PENDING/AWAITING_APPROVAL/CONFIRMED),
 *                      owner (PENDING/AWAITING_APPROVAL/CONFIRMED), or admin.
 *                      Refund issued automatically if payment was already captured.
 * ACTIVE             → Truck handed off; odometer_start recorded by owner
 * COMPLETED          → Truck returned; odometer_end recorded; final amount calculated
 *
 * Normal flow:
 *   PENDING → [renter pays] → AWAITING_APPROVAL → [owner approves] → CONFIRMED
 *                                                → [owner rejects] → REJECTED (+ refund)
 *   CONFIRMED → [owner handoff] → ACTIVE → [owner return] → COMPLETED
 *
 * NOTE: REJECTED is a terminal state (owner declined, refund issued).
 * Future: rental extension requests will be a separate entity/flow, not a new status here.
 */
public enum BookingStatus {
    PENDING,
    AWAITING_APPROVAL,
    CONFIRMED,
    REJECTED,
    CANCELLED,
    ACTIVE,
    COMPLETED
}
