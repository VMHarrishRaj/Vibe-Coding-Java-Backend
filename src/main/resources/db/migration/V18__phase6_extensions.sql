-- ═══════════════════════════════════════════════════
-- V18__phase6_extensions.sql
-- ═══════════════════════════════════════════════════
-- Phase 6 Extensions:
-- 1. trucks — add year, color, fuel_type, vin_number
-- 2. bookings — add insurance_cost, additional_services_cost, tax
-- 3. payment_transactions — add invoice_number, card_last4, payment_method
--                         + invoice_seq sequence for INV001, INV002...
--
-- ALL new columns are nullable — zero impact on existing rows.
-- ═══════════════════════════════════════════════════

-- ── 1. trucks ──
ALTER TABLE trucks ADD COLUMN year        INTEGER;
ALTER TABLE trucks ADD COLUMN color       VARCHAR(100);
ALTER TABLE trucks ADD COLUMN fuel_type   VARCHAR(20);
ALTER TABLE trucks ADD COLUMN vin_number  VARCHAR(17);

-- ── 2. bookings ──
ALTER TABLE bookings ADD COLUMN insurance_cost             NUMERIC(12,2);
ALTER TABLE bookings ADD COLUMN additional_services_cost   NUMERIC(12,2);
ALTER TABLE bookings ADD COLUMN tax                        NUMERIC(12,2);

-- ── 3. payment_transactions ──
-- Invoice sequence: generates 1, 2, 3... formatted as INV001, INV002...
CREATE SEQUENCE IF NOT EXISTS invoice_seq START 1 INCREMENT 1;

ALTER TABLE payment_transactions ADD COLUMN invoice_number  VARCHAR(20) UNIQUE;
ALTER TABLE payment_transactions ADD COLUMN card_last4      VARCHAR(4);
ALTER TABLE payment_transactions ADD COLUMN payment_method  VARCHAR(50);
