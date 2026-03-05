-- ═══════════════════════════════════════════════════
-- V2__create_users_table.sql
-- ═══════════════════════════════════════════════════
-- Creates the users table with FK to roles.
-- This is the foundation for authentication.
-- ═══════════════════════════════════════════════════

CREATE TABLE users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id           INT          NOT NULL REFERENCES roles(id),
    email             VARCHAR(255) NOT NULL UNIQUE,
    phone             VARCHAR(20)  NOT NULL UNIQUE,
    password_hash     VARCHAR(255) NOT NULL,
    fullname          VARCHAR(255) NOT NULL,
    dob               DATE,
    address           VARCHAR(500),
    city              VARCHAR(100),
    state             VARCHAR(100),
    country           VARCHAR(100) DEFAULT 'India',
    zipcode           VARCHAR(10),
    status            VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    kyc_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    profile_image_url VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION'))
);

-- Index for login lookups (email is already unique, but explicit index helps)
CREATE INDEX idx_users_email ON users(email);
-- Index for phone-based lookups
CREATE INDEX idx_users_phone ON users(phone);
-- Index for role-based queries (e.g., "list all owners")
CREATE INDEX idx_users_role ON users(role_id);
-- Index for soft-delete filtering
CREATE INDEX idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NULL;
