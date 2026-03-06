-- Phase 4.5 Fix H2: Allow re-registration of soft-deleted truck reg numbers
-- Drop the blanket unique constraint
ALTER TABLE trucks DROP CONSTRAINT IF EXISTS trucks_registration_number_key;

-- Add partial unique index: unique only among non-deleted trucks
CREATE UNIQUE INDEX trucks_registration_number_active_idx
    ON trucks(registration_number)
    WHERE deleted_at IS NULL;
