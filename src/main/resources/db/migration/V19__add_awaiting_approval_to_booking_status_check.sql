-- V19: Add AWAITING_APPROVAL to bookings status CHECK constraint
--
-- The original CHECK in V10 was written before AWAITING_APPROVAL was introduced
-- as a booking lifecycle state (payment captured → owner must confirm).
-- PostgreSQL CHECK constraints cannot be altered in-place — drop and recreate.

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_status_check;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check
        CHECK (status IN (
            'PENDING',
            'AWAITING_APPROVAL',
            'CONFIRMED',
            'REJECTED',
            'CANCELLED',
            'ACTIVE',
            'COMPLETED'
        ));
