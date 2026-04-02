package com.truckhire.modules.truck.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import com.truckhire.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Truck Entity — maps to the 'trucks' table.
 *
 * Represents a truck listed by an OWNER on the platform.
 * Must be APPROVED by admin before it is visible to renters.
 *
 * LIFECYCLE:
 * Owner adds truck → PENDING_APPROVAL → Admin approves → APPROVED (visible)
 * → Admin rejects → REJECTED
 * Owner can deactivate → INACTIVE (hidden from search)
 *
 * GUARD: Only KYC-verified owners can add trucks.
 */
@Entity
@Table(name = "trucks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Truck extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_type_id", nullable = false)
    private VehicleType vehicleType;

    @Column(name = "registration_number", nullable = false, unique = true, length = 50)
    private String registrationNumber;

    @Column(nullable = false, length = 255)
    private String model;

    @Column(nullable = false, length = 255)
    private String make;

    @Column(name = "price_per_day", nullable = false, precision = 12, scale = 2)
    private BigDecimal pricePerDay;

    @Column(name = "cost_per_mile", precision = 10, scale = 2, nullable = false)
    private BigDecimal costPerMile;

    @Column(name = "location_city", nullable = false, length = 255)
    private String locationCity;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(name = "capacity_tons")
    private Integer capacityTons;

    @Column(length = 100)
    private String torque;

    @Column(name = "mileage_total")
    @Builder.Default
    private Integer mileageTotal = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private TruckStatus status = TruckStatus.PENDING_APPROVAL;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "year")
    private Integer year;

    @Column(name = "color", length = 100)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", length = 20)
    private FuelType fuelType;

    @Column(name = "vin_number", length = 17)
    private String vinNumber;

    @OneToMany(mappedBy = "truck", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PickupLocation> pickupLocations = new ArrayList<>();
}
