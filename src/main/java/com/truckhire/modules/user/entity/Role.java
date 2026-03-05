package com.truckhire.modules.user.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Role Entity — maps to the 'roles' lookup table.
 *
 * This is a LOOKUP table, not an audit table.
 * It does NOT extend BaseAuditEntity because:
 * - Roles are seeded at startup and rarely change
 * - They don't need created_at/updated_at/deleted_at
 * - They use simple int IDs (not UUIDs)
 *
 * The roles table was created and seeded in V1__create_lookup_tables.sql:
 * 1 = ADMIN
 * 2 = OWNER
 * 3 = RENTER
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // SERIAL in PostgreSQL
    private Integer id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(length = 255)
    private String description;

    // ── Constants for role names (used in @PreAuthorize) ──
    public static final String ADMIN = "ADMIN";
    public static final String OWNER = "OWNER";
    public static final String RENTER = "RENTER";
}
