-- Phase 10: OTP storage migrated from DB → Redis
-- pending_registrations and password_reset_tokens are replaced by Redis keys with TTL.
-- These tables are no longer read or written by the application.

DROP TABLE IF EXISTS pending_registrations;
DROP TABLE IF EXISTS password_reset_tokens;
