-- ═══════════════════════════════════════════════════
-- V16__add_payment_tables.sql
-- ═══════════════════════════════════════════════════
-- Phase 6 — Payment Integration
--
-- CHANGES:
-- 1. Create platform_settings table (admin-configurable, single-row)
-- 2. Create payment_transactions table (full payment audit trail)
-- 3. Add payment gateway ID columns to users (for owner payouts)
-- ═══════════════════════════════════════════════════

-- ── 1. platform_settings ──
-- Single-row config table. Admin can switch active gateway and currency
-- via API without redeploying. id=1 is always the only row.
CREATE TABLE platform_settings (
    id                   SERIAL PRIMARY KEY,
    active_gateway       VARCHAR(20)    NOT NULL DEFAULT 'RAZORPAY',
    active_currency      VARCHAR(3)     NOT NULL DEFAULT 'INR',
    platform_fee_percent NUMERIC(5,2)   NOT NULL DEFAULT 10.00,
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by           UUID           REFERENCES users(id)
);

-- Seed the default row (Razorpay + INR for demo)
INSERT INTO platform_settings (active_gateway, active_currency, platform_fee_percent)
VALUES ('RAZORPAY', 'INR', 10.00);

-- ── 2. payment_transactions ──
-- One row per payment event (CHARGE / REFUND / PAYOUT).
-- Extends BaseAuditEntity — must include created_at, updated_at, deleted_at.
CREATE TABLE payment_transactions (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id           UUID           NOT NULL REFERENCES bookings(id),
    gateway              VARCHAR(20)    NOT NULL,
    gateway_order_id     VARCHAR(255),
    gateway_payment_id   VARCHAR(255),
    gateway_signature    VARCHAR(512),
    gateway_transfer_id  VARCHAR(255),
    amount               NUMERIC(12,2)  NOT NULL,
    platform_fee         NUMERIC(12,2),
    owner_amount         NUMERIC(12,2),
    currency             VARCHAR(3)     NOT NULL DEFAULT 'INR',
    status               VARCHAR(30)    NOT NULL,
    type                 VARCHAR(20)    NOT NULL,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX idx_payment_txn_booking ON payment_transactions(booking_id);
CREATE INDEX idx_payment_txn_order   ON payment_transactions(gateway_order_id);
CREATE INDEX idx_payment_txn_status  ON payment_transactions(status);

-- ── 3. Add payment gateway ID columns to users ──
-- razorpay_contact_id / razorpay_fund_account_id: set during owner onboarding for payouts
-- stripe_account_id: Stripe Connect account ID for owner payouts
ALTER TABLE users ADD COLUMN razorpay_contact_id     VARCHAR(255);
ALTER TABLE users ADD COLUMN razorpay_fund_account_id VARCHAR(255);
ALTER TABLE users ADD COLUMN stripe_account_id        VARCHAR(255);
