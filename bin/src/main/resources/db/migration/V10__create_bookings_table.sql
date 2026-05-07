-- V10: Create bookings tables + booking sequence
-- Phase 5 — Booking Engine

-- Sequence for human-readable booking numbers (BK001, BK002, ...)
CREATE SEQUENCE booking_seq START 1 INCREMENT 1;

CREATE TABLE bookings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_number      VARCHAR(20) NOT NULL UNIQUE,
    truck_id            UUID NOT NULL REFERENCES trucks(id),
    renter_id           UUID NOT NULL REFERENCES users(id),
    owner_id            UUID NOT NULL REFERENCES users(id),

    -- Dates
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    total_days          INT NOT NULL,

    -- Price snapshots (captured at booking time, immutable)
    price_per_day       DECIMAL(12,2) NOT NULL,
    cost_per_mile       DECIMAL(10,2) NOT NULL DEFAULT 0,

    -- Day-based amount (price_per_day * total_days) — known at creation
    day_amount          DECIMAL(14,2) NOT NULL,

    -- Odometer / mileage (filled by owner at handoff and return)
    odometer_start      INT,
    odometer_end        INT,
    miles_driven        INT,
    mileage_amount      DECIMAL(14,2),

    -- Final total (day_amount + mileage_amount, set on COMPLETED)
    total_amount        DECIMAL(14,2),

    -- Locations (free text for now)
    pickup_location     VARCHAR(500),
    dropoff_location    VARCHAR(500),

    -- Notes / reasons
    renter_notes        TEXT,
    owner_notes         TEXT,
    cancellation_reason TEXT,

    -- Status
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','CONFIRMED','REJECTED','CANCELLED','ACTIVE','COMPLETED')),

    -- Timestamps
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled_at        TIMESTAMPTZ,
    handed_off_at       TIMESTAMPTZ,
    returned_at         TIMESTAMPTZ
);

CREATE TABLE booking_status_history (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  UUID NOT NULL REFERENCES bookings(id),
    status      VARCHAR(30) NOT NULL,
    changed_by  UUID NOT NULL REFERENCES users(id),
    notes       TEXT,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_bookings_truck        ON bookings(truck_id);
CREATE INDEX idx_bookings_renter       ON bookings(renter_id);
CREATE INDEX idx_bookings_owner        ON bookings(owner_id);
CREATE INDEX idx_bookings_status       ON bookings(status);
CREATE INDEX idx_bookings_dates        ON bookings(start_date, end_date);
CREATE INDEX idx_bookings_truck_active ON bookings(truck_id, status) WHERE status IN ('CONFIRMED','ACTIVE');
CREATE INDEX idx_booking_history       ON booking_status_history(booking_id);
