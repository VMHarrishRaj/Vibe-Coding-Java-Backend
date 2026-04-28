package com.truckhire.modules.truck.entity;

/**
 * Truck status.
 *
 * AVAILABLE  → truck is live and bookable
 * UNAVAILABLE → owner put it in maintenance, or owner was suspended by admin
 */
public enum TruckStatus {
    AVAILABLE,
    UNAVAILABLE
}
