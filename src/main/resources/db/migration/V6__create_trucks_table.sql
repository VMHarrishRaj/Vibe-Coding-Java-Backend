-- ═══════════════════════════════════════════════════
-- V6__create_trucks_table.sql
-- ═══════════════════════════════════════════════════
-- Trucks + truck documents tables.
-- Owner adds trucks (PENDING_APPROVAL) → Admin approves/rejects.
-- Truck photos stored on disk, file_path in DB.
-- ═══════════════════════════════════════════════════

CREATE TABLE trucks (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id              UUID           NOT NULL REFERENCES users(id),
    vehicle_type_id       INT            NOT NULL REFERENCES vehicle_types(id),
    registration_number   VARCHAR(50)    NOT NULL UNIQUE,
    model                 VARCHAR(255)   NOT NULL,
    make                  VARCHAR(255)   NOT NULL,
    price_per_day         DECIMAL(12,2)  NOT NULL,
    cost_per_mile         DECIMAL(10,2),
    location_city         VARCHAR(255)   NOT NULL,
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    capacity_tons         INT,
    torque                VARCHAR(100),
    mileage_total         INT            DEFAULT 0,
    status                VARCHAR(30)    NOT NULL DEFAULT 'PENDING_APPROVAL',
    description           TEXT,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,

    CONSTRAINT chk_truck_status
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'INACTIVE'))
);

-- Indexes
CREATE INDEX idx_trucks_owner ON trucks(owner_id);
CREATE INDEX idx_trucks_status ON trucks(status);
CREATE INDEX idx_trucks_location ON trucks(location_city);
CREATE INDEX idx_trucks_deleted ON trucks(deleted_at) WHERE deleted_at IS NULL;

-- ═══════════════════════════════════════════════════
-- TRUCK_DOCUMENTS table
-- ═══════════════════════════════════════════════════
-- Stores photos and docs for each truck.
-- file_path is relative (e.g., "trucks/truck-uuid/uuid_photo.jpg").

CREATE TABLE truck_documents (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id          UUID         NOT NULL REFERENCES trucks(id),
    document_type_id  INT          NOT NULL REFERENCES document_types(id),
    file_path         VARCHAR(1000) NOT NULL,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_truck_documents_truck ON truck_documents(truck_id);
