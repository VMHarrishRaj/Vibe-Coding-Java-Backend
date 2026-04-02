package com.truckhire.modules.truck.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A single pickup location city for a truck.
 * A truck can have multiple pickup locations (SCRUM-87/88).
 *
 * Hard-delete semantics: locations are replaced atomically on PUT /trucks/{id}.
 * Does NOT extend BaseAuditEntity — no soft-delete, no updated_at needed.
 * The DB-level DEFAULT now() on created_at is sufficient.
 */
@Entity
@Table(
    name = "pickup_locations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"truck_id", "city"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickupLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @Column(nullable = false, length = 255)
    private String city;
}
