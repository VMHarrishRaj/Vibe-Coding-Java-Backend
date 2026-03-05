-- ═══════════════════════════════════════════════════
-- V3__seed_default_admin.sql
-- ═══════════════════════════════════════════════════
-- Seeds a default admin user for the platform.
--
-- WHY:
--   The platform needs at least one admin to exist
--   before any owner/renter management can happen.
--   This admin is the bootstrap account.
--
-- CREDENTIALS:
--   Email:    admin@truckhire.com
--   Password: Admin@123456  (BCrypt hashed below)
--
-- IMPORTANT (Production):
--   Change the admin password immediately after first
--   deployment. Consider using env-variable-based
--   seeding for production environments.
-- ═══════════════════════════════════════════════════

INSERT INTO users (
    id,
    role_id,
    email,
    phone,
    password_hash,
    fullname,
    status,
    kyc_verified,
    created_at
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM roles WHERE name = 'ADMIN'),
    'admin@truckhire.com',
    '+910000000000',
    -- BCrypt hash of 'Admin@123456' (cost factor 10)
    '$2a$10$W9sl56z2spV8B9TW/76bP.mSK8UFY33Spb.46Wlj/yvkp3wyGvl1W',
    'System Admin',
    'ACTIVE',
    FALSE,
    NOW()
);
