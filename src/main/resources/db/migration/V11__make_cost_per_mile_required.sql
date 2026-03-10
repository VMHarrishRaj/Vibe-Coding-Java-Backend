-- Backfill any existing nulls to 0 before adding NOT NULL constraint
UPDATE trucks SET cost_per_mile = 0 WHERE cost_per_mile IS NULL;
ALTER TABLE trucks ALTER COLUMN cost_per_mile SET NOT NULL;
ALTER TABLE trucks ALTER COLUMN cost_per_mile SET DEFAULT 0;
