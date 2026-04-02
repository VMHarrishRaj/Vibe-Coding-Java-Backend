-- V22: Addon Services — Insurance, RSA, Equipment
-- Adds: addon_service_types, truck_equipment, truck_insurance, booking_addons tables
-- Adds: trucks.insured boolean column

-- ── Admin-managed catalog: Insurance plans and RSA providers ─────────────────
CREATE TABLE addon_service_types (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type          VARCHAR(30)  NOT NULL,                    -- INSURANCE | RSA | EQUIPMENT | ACCESSORY | DRIVER
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    provider      VARCHAR(255),                             -- company/provider name
    rate          NUMERIC(12,2) NOT NULL,                   -- per unit rate
    rate_unit     VARCHAR(20)  NOT NULL DEFAULT 'PER_DAY',  -- PER_DAY | PER_SERVICE | PER_HOUR
    contact_phone VARCHAR(30),
    contact_email VARCHAR(255),
    availability  VARCHAR(100),                             -- e.g. "24/7", "Mon-Sat 8AM-8PM"
    max_coverage  NUMERIC(12,2),                            -- insurance: max claim value
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | INACTIVE | PENDING
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_addon_service_types_type   ON addon_service_types(type)   WHERE deleted_at IS NULL;
CREATE INDEX idx_addon_service_types_status ON addon_service_types(status) WHERE deleted_at IS NULL;

-- ── Owner-managed equipment per truck ────────────────────────────────────────
CREATE TABLE truck_equipment (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id   UUID         NOT NULL REFERENCES trucks(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    quantity   INTEGER      NOT NULL DEFAULT 1,
    condition  VARCHAR(50),                                 -- EXCELLENT | GOOD | FAIR
    rate       NUMERIC(12,2) NOT NULL,                     -- per-day rate
    status     VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | IN_USE | UNAVAILABLE | PENDING
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_truck_equipment_truck ON truck_equipment(truck_id) WHERE deleted_at IS NULL;

-- ── Insurance plans attached to a truck by its owner ─────────────────────────
CREATE TABLE truck_insurance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id         UUID         NOT NULL REFERENCES trucks(id) ON DELETE CASCADE,
    addon_service_id UUID         NOT NULL REFERENCES addon_service_types(id),
    policy_number    VARCHAR(100),
    effective_from   DATE         NOT NULL,
    effective_to     DATE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_truck_active_insurance UNIQUE (truck_id, addon_service_id)
);

CREATE INDEX idx_truck_insurance_truck ON truck_insurance(truck_id) WHERE deleted_at IS NULL;

-- ── Snapshot of addons selected by renter at booking creation ─────────────────
CREATE TABLE booking_addons (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         UUID          NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    addon_service_id   UUID          REFERENCES addon_service_types(id),  -- NULL for equipment
    truck_equipment_id UUID          REFERENCES truck_equipment(id),       -- NULL for service addons
    addon_type         VARCHAR(30)   NOT NULL,   -- INSURANCE | RSA | EQUIPMENT | ACCESSORY | DRIVER
    name_snapshot      VARCHAR(255)  NOT NULL,   -- captured at booking time — immutable
    rate_snapshot      NUMERIC(12,2) NOT NULL,   -- captured at booking time — immutable
    quantity           INTEGER       NOT NULL DEFAULT 1,
    total_cost         NUMERIC(12,2) NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_addons_booking ON booking_addons(booking_id);

-- ── Add insured flag to trucks ────────────────────────────────────────────────
ALTER TABLE trucks ADD COLUMN insured BOOLEAN NOT NULL DEFAULT FALSE;
