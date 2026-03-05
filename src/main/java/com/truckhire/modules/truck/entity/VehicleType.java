package com.truckhire.modules.truck.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * VehicleType Entity — maps to the 'vehicle_types' lookup table.
 * Classifies trucks by size/category (MINI, STANDARD, HEAVY, etc.).
 */
@Entity
@Table(name = "vehicle_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
