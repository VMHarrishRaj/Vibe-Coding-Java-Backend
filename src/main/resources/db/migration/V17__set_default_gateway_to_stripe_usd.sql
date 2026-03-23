-- ═══════════════════════════════════════════════════
-- V17__set_default_gateway_to_stripe_usd.sql
-- ═══════════════════════════════════════════════════
-- Change the platform default from RAZORPAY/INR → STRIPE/USD.
--
-- Why a migration instead of changing V16?
-- V16 has already run on the company DB. Modifying an executed migration
-- causes a Flyway checksum mismatch. A new migration is the correct approach.
--
-- Admin can still switch back to RAZORPAY/INR via:
--   PUT /admin/platform/settings
-- This migration only sets the runtime default — it does NOT remove Razorpay support.
-- ═══════════════════════════════════════════════════

UPDATE platform_settings
SET    active_gateway  = 'STRIPE',
       active_currency = 'USD',
       updated_at      = now()
WHERE  id = 1;
