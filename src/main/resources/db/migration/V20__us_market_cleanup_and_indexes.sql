-- ═══════════════════════════════════════════════════
-- V20__us_market_cleanup_and_indexes.sql
-- ═══════════════════════════════════════════════════
-- US market cleanup:
-- 1. Rename PETROL → GASOLINE (British/Indian English → US English)
-- 2. Remove DEFAULT 'India' from users.country (left over from India-market build)
-- 3. Composite partial indexes for admin user list performance (SCRUM-41)
--
-- All changes are non-destructive. No data is dropped.
-- ═══════════════════════════════════════════════════

-- ── 1. FuelType rename ──
-- Java enum FuelType.PETROL renamed to FuelType.GASOLINE.
-- Update any existing rows so the DB string matches the new enum value.
UPDATE trucks SET fuel_type = 'GASOLINE' WHERE fuel_type = 'PETROL';

-- ── 2. Remove India country default ──
-- V2 set DEFAULT 'India' — no longer appropriate for the US market.
-- Existing rows are unaffected; NULL is now valid for country.
ALTER TABLE users ALTER COLUMN country DROP DEFAULT;

-- ── 3. Composite partial indexes (SCRUM-41) ──
-- The most common admin user-list queries filter by deleted_at IS NULL
-- combined with role_id, status, or created_at. Single-column indexes on
-- those columns force PG to do two index scans + bitmap AND. These composites
-- collapse that into one index scan per query pattern.
CREATE INDEX idx_users_deleted_role    ON users(deleted_at, role_id)         WHERE deleted_at IS NULL;
CREATE INDEX idx_users_deleted_status  ON users(deleted_at, status)          WHERE deleted_at IS NULL;
CREATE INDEX idx_users_deleted_created ON users(deleted_at, created_at DESC) WHERE deleted_at IS NULL;
