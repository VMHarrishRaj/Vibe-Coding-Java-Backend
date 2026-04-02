-- ═══════════════════════════════════════════════════
-- V21__add_pickup_locations.sql
-- ═══════════════════════════════════════════════════
-- SCRUM-87 / SCRUM-88: Multiple pickup locations per truck.
--
-- A truck can now have multiple pickup cities (e.g. "New York", "Boston").
-- This replaces the single locationCity field for booking pickup selection.
-- locationCity on the trucks table is kept as the truck's base/primary location.
--
-- Hard-delete semantics: rows are replaced atomically on PUT /trucks/{id}.
-- No soft-delete, no updated_at — only created_at (DB default).
--
-- ON DELETE CASCADE: if a truck is hard-deleted its pickup_locations go too.
-- In normal use trucks are soft-deleted (deleted_at), so cascade never fires.
-- ═══════════════════════════════════════════════════

CREATE TABLE pickup_locations (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    truck_id   UUID        NOT NULL REFERENCES trucks(id) ON DELETE CASCADE,
    city       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pickup_location_truck_city UNIQUE (truck_id, city)
);

CREATE INDEX idx_pickup_locations_truck ON pickup_locations(truck_id);
