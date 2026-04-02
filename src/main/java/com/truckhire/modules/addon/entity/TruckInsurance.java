package com.truckhire.modules.addon.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import com.truckhire.modules.truck.entity.Truck;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Records which insurance plan an owner has attached to their truck.
 * When at least one active TruckInsurance exists, trucks.insured is set to true.
 * A truck can only have one active insurance plan per addon_service_type
 * (enforced by uq_truck_active_insurance DB constraint).
 */
@Entity
@Table(name = "truck_insurance",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_truck_active_insurance",
                columnNames = {"truck_id", "addon_service_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TruckInsurance extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "addon_service_id", nullable = false)
    private AddonServiceType insurancePlan;

    @Column(name = "policy_number", length = 100)
    private String policyNumber;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
