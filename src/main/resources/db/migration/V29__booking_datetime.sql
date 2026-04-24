-- V29: Migrate booking start_date / end_date from DATE to TIMESTAMPTZ
--
-- Existing DATE values represent the start of that day in UTC.
-- The USING clause converts them to midnight UTC, preserving all
-- existing booking ranges exactly — no data is lost or shifted.
--
-- After this migration, callers may pass full timestamps (e.g. 2026-05-10T14:00)
-- or date-only strings (parsed to midnight by the application layer).
-- Day-based pricing is unchanged — totalDays is still computed from date parts only.

ALTER TABLE bookings
    ALTER COLUMN start_date TYPE TIMESTAMPTZ
        USING start_date::TIMESTAMPTZ,
    ALTER COLUMN end_date   TYPE TIMESTAMPTZ
        USING end_date::TIMESTAMPTZ;
