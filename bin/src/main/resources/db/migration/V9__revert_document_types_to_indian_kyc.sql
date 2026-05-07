-- ═══════════════════════════════════════════════════
-- V9__revert_document_types_to_indian_kyc.sql
-- ═══════════════════════════════════════════════════
-- CONTEXT: During a US-market revert, document_types
-- were left with DRIVER_LICENSE / PASSPORT instead of
-- the correct AADHAAR / PAN / LICENSE used by the
-- codebase and KycService validation.
--
-- This migration corrects the DB to match the code.
--
-- NOTE: This is a data-correction migration. Once the
-- DB is stable and all environments are in sync, this
-- can be squashed into V1 and removed from history via
-- flyway:clean + flyway:migrate on a fresh setup.
-- ═══════════════════════════════════════════════════

-- Remove US-market KYC types that don't belong
DELETE FROM document_types WHERE name IN ('DRIVER_LICENSE', 'PASSPORT') AND category = 'KYC';

-- Remove US-market vehicle doc type if present
DELETE FROM document_types WHERE name = 'TRUCK_PHOTO' AND category = 'VEHICLE';

-- Insert correct Indian KYC document types (idempotent)
INSERT INTO document_types (name, category)
    SELECT 'AADHAAR', 'KYC' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'AADHAAR');

INSERT INTO document_types (name, category)
    SELECT 'PAN', 'KYC' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'PAN');

INSERT INTO document_types (name, category)
    SELECT 'LICENSE', 'KYC' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'LICENSE');

-- Ensure core vehicle types exist (idempotent)
INSERT INTO document_types (name, category)
    SELECT 'RC', 'VEHICLE' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'RC');

INSERT INTO document_types (name, category)
    SELECT 'INSURANCE', 'VEHICLE' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'INSURANCE');

INSERT INTO document_types (name, category)
    SELECT 'PERMIT', 'VEHICLE' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'PERMIT');

INSERT INTO document_types (name, category)
    SELECT 'PHOTO', 'VEHICLE' WHERE NOT EXISTS (SELECT 1 FROM document_types WHERE name = 'PHOTO');
