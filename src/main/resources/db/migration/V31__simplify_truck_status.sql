-- V31: Simplify truck status — remove approval workflow.
-- Trucks are live immediately on registration.
-- AVAILABLE   = live and bookable
-- UNAVAILABLE = maintenance (owner-set) or owner suspended by admin
--
-- Strategy: widen the CHECK constraint first (additive), then migrate data.
-- This is safe to run against a live DB — no columns or constraints are dropped.
-- The constraint will be tightened to only AVAILABLE/UNAVAILABLE in a future deployment.

-- Step 1: Widen constraint to include new values alongside old ones
ALTER TABLE trucks DROP CONSTRAINT IF EXISTS chk_truck_status;
ALTER TABLE trucks
    ADD CONSTRAINT chk_truck_status
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'INACTIVE', 'AVAILABLE', 'UNAVAILABLE'));

-- Step 2: Migrate all existing rows to the new two-value model
UPDATE trucks SET status = 'AVAILABLE'   WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED');
UPDATE trucks SET status = 'UNAVAILABLE' WHERE status = 'INACTIVE';

-- Step 3: Change the default for new rows
ALTER TABLE trucks ALTER COLUMN status SET DEFAULT 'AVAILABLE';
