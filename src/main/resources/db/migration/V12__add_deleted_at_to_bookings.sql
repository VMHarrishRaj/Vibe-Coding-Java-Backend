-- V12: Add deleted_at to bookings table
-- Required because Booking extends BaseAuditEntity which maps this column.
-- Bookings are not currently soft-deleted but the column must exist for Hibernate schema validation.
ALTER TABLE bookings ADD COLUMN deleted_at TIMESTAMPTZ;
