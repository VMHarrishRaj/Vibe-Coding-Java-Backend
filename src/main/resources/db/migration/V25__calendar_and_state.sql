-- V25: Owner availability calendar (blocked dates) + location_state on trucks

ALTER TABLE trucks
    ADD COLUMN IF NOT EXISTS location_state VARCHAR(100);

CREATE TABLE IF NOT EXISTS truck_blocked_dates (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    truck_id      UUID        NOT NULL REFERENCES trucks(id) ON DELETE CASCADE,
    blocked_date  DATE        NOT NULL,
    CONSTRAINT uq_truck_blocked_date UNIQUE (truck_id, blocked_date)
);

CREATE INDEX IF NOT EXISTS idx_truck_blocked_dates_truck_id ON truck_blocked_dates(truck_id);
CREATE INDEX IF NOT EXISTS idx_truck_blocked_dates_date     ON truck_blocked_dates(blocked_date);
