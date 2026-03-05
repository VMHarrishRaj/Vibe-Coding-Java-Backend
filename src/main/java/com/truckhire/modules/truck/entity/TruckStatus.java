package com.truckhire.modules.truck.entity;

/**
 * Truck approval status.
 *
 * PENDING_APPROVAL → Owner just added the truck, awaiting admin review
 * APPROVED → Admin approved, truck visible to renters
 * REJECTED → Admin rejected (reason stored on the truck/action)
 * INACTIVE → Owner deactivated the truck (soft toggle, not delete)
 */
public enum TruckStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    INACTIVE
}
