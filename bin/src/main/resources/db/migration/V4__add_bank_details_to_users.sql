-- ═══════════════════════════════════════════════════
-- V4__add_bank_details_to_users.sql
-- ═══════════════════════════════════════════════════
-- Adds bank account details to users table.
-- Required for owner settlement payouts (Phase 7).
-- All fields are nullable — owners can add them
-- during registration or update later via PUT /users/me.
-- ═══════════════════════════════════════════════════

ALTER TABLE users ADD COLUMN bank_account_name   VARCHAR(255);
ALTER TABLE users ADD COLUMN bank_account_number VARCHAR(50);
ALTER TABLE users ADD COLUMN bank_ifsc_code      VARCHAR(20);
ALTER TABLE users ADD COLUMN bank_name           VARCHAR(255);
