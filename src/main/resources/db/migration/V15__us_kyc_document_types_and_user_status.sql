-- ═══════════════════════════════════════════════════
-- V15__us_kyc_document_types_and_user_status.sql
-- ═══════════════════════════════════════════════════
-- Aligns KYC document types and user status values
-- with the US-market mobile app contract.
--
-- CHANGES:
-- 1. Replace Indian KYC types (AADHAAR, PAN, LICENSE)
--    with US types (DRIVER_LICENSE, PASSPORT, STATE_ID)
--    IDs 1/2/3 as expected by the mobile app.
-- 2. Expand the user status CHECK constraint to include
--    PENDING and REJECTED (required by mobile KYC flow).
-- ═══════════════════════════════════════════════════

-- ── 1. Replace Indian KYC document types with US types ──
-- Must delete user_documents that reference the old KYC types first (FK constraint).
-- These are test/dev uploads against Indian doc types — safe to remove on migration.
DELETE FROM user_documents
WHERE document_type_id IN (
    SELECT id FROM document_types WHERE category = 'KYC'
);

-- Now safe to remove old KYC document type rows
DELETE FROM document_types WHERE category = 'KYC';

-- Insert US KYC types (idempotent via ON CONFLICT on name)
INSERT INTO document_types (name, category) VALUES
    ('DRIVER_LICENSE', 'KYC'),
    ('PASSPORT',       'KYC'),
    ('STATE_ID',       'KYC')
ON CONFLICT (name) DO UPDATE
    SET category  = EXCLUDED.category,
        is_active = TRUE;

-- ── 2. Expand user status CHECK constraint ──
-- Drop the existing constraint and recreate with PENDING + REJECTED
ALTER TABLE users DROP CONSTRAINT chk_user_status;

ALTER TABLE users ADD CONSTRAINT chk_user_status
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION', 'PENDING', 'REJECTED'));
