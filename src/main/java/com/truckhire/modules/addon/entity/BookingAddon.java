package com.truckhire.modules.addon.entity;

import com.truckhire.modules.booking.entity.Booking;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of an addon selected by a renter at booking creation.
 * Prices are snapshotted — changes to the source plan do not affect this row.
 */
@Entity
@Table(name = "booking_addons")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Non-null for INSURANCE and RSA addons */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "addon_service_id")
    private AddonServiceType addonServiceType;

    /** Non-null for EQUIPMENT addons */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_equipment_id")
    private TruckEquipment truckEquipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "addon_type", nullable = false, length = 30)
    private AddonType addonType;

    /** Name captured at booking time — immutable after creation */
    @Column(name = "name_snapshot", nullable = false, length = 255)
    private String nameSnapshot;

    /** Rate per unit captured at booking time — immutable after creation */
    @Column(name = "rate_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal rateSnapshot;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(name = "total_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalCost;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
