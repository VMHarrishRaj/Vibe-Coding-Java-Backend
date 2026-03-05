-- ═══════════════════════════════════════════════════
-- V1__create_lookup_tables.sql
-- ═══════════════════════════════════════════════════
-- Flyway Migration: V1 (Version 1)
--
-- NAMING CONVENTION:
--   V{version}__{description}.sql
--   - V1, V2, V3... (sequential, never skip)
--   - Double underscore between version and description
--   - Flyway runs each migration ONCE, then records it
--   - If a migration is already applied, it's skipped
--
-- THIS MIGRATION CREATES:
--   1. Lookup/master data tables (ROLE, VEHICLE_TYPE, etc.)
--   2. Seeds them with initial data
--
-- WHY LOOKUP TABLES FIRST?
--   Because all other tables reference them via FK.
--   They must exist before users, trucks, bookings.
-- ═══════════════════════════════════════════════════

-- ── Enable UUID extension (PostgreSQL) ──
-- This lets us use gen_random_uuid() to auto-generate UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ═══════════════════════════════════════
-- ROLE table
-- ═══════════════════════════════════════
-- Controls what a user can do in the system.
-- Referenced by: users.role_id
CREATE TABLE roles (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255)
);

-- Seed initial roles
INSERT INTO roles (name, description) VALUES
    ('ADMIN',  'Platform administrator — full access'),
    ('OWNER',  'Truck owner — manages trucks and bookings'),
    ('RENTER', 'Renter — browses and books trucks');

-- ═══════════════════════════════════════
-- VEHICLE_TYPE table
-- ═══════════════════════════════════════
-- Classifies trucks by size/category.
-- Referenced by: trucks.vehicle_type_id
CREATE TABLE vehicle_types (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Seed initial vehicle types
INSERT INTO vehicle_types (name, description) VALUES
    ('MINI',     'Small trucks, ideal for local moves'),
    ('STANDARD', 'Medium trucks for intercity transport'),
    ('HEAVY',    'Heavy-duty trucks for large cargo');

-- ═══════════════════════════════════════
-- DOCUMENT_TYPE table
-- ═══════════════════════════════════════
-- Types of documents that can be uploaded.
-- category = 'VEHICLE' for truck docs, 'KYC' for user docs.
-- Referenced by: truck_documents.document_type_id, user_documents.document_type_id
CREATE TABLE document_types (
    id        SERIAL PRIMARY KEY,
    name      VARCHAR(50)  NOT NULL UNIQUE,
    category  VARCHAR(20)  NOT NULL,  -- 'VEHICLE' or 'KYC'
    is_active BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT chk_document_category CHECK (category IN ('VEHICLE', 'KYC'))
);

-- Seed document types
INSERT INTO document_types (name, category) VALUES
    ('RC',        'VEHICLE'),
    ('INSURANCE', 'VEHICLE'),
    ('PERMIT',    'VEHICLE'),
    ('PHOTO',     'VEHICLE'),
    ('AADHAAR',   'KYC'),
    ('PAN',       'KYC'),
    ('LICENSE',   'KYC');

-- ═══════════════════════════════════════
-- ADD_ON_CATEGORY table
-- ═══════════════════════════════════════
-- Categories for add-on services.
-- Referenced by: add_on_services.category_id
CREATE TABLE add_on_categories (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Seed add-on categories
INSERT INTO add_on_categories (name, description) VALUES
    ('INSURANCE',   'Vehicle and cargo insurance options'),
    ('RSA',         'Roadside assistance services'),
    ('EQUIPMENT',   'Additional equipment like GPS, loading tools'),
    ('ACCESSORIES', 'Comfort and utility accessories');
