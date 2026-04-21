-- V26: Track which INACTIVE trucks were deactivated by an admin owner-suspension
-- vs. owner manually deactivating. Needed to correctly restore trucks on re-activation.
ALTER TABLE trucks
    ADD COLUMN IF NOT EXISTS suspended_by_admin BOOLEAN NOT NULL DEFAULT FALSE;
