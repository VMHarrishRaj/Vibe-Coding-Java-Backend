package com.truckhire.modules.addon.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import com.truckhire.modules.truck.entity.Truck;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Equipment items an owner has registered against one of their trucks.
 * Renters can select these at booking time and pay the daily rate.
 */
@Entity
@Table(name = "truck_equipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TruckEquipment extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /** EXCELLENT | GOOD | FAIR */
    @Column(length = 50)
    private String condition;

    /** Per-day rental rate for this equipment item */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    /** AVAILABLE | IN_USE | UNAVAILABLE | PENDING */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "AVAILABLE";
}
