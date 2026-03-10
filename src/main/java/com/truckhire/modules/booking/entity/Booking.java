package com.truckhire.modules.booking.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Booking Entity — maps to the 'bookings' table.
 *
 * Represents a truck rental request from a RENTER to an OWNER.
 *
 * COST MODEL:
 * total_cost = (total_days × price_per_day) + (miles_driven × cost_per_mile)
 * - day_amount is known at booking creation
 * - mileage_amount is calculated when the truck is returned
 *
 * LIFECYCLE:
 * PENDING → CONFIRMED (owner accepts) or REJECTED (owner declines)
 * CONFIRMED → ACTIVE (owner records odometer_start at handoff)
 * ACTIVE → COMPLETED (owner records odometer_end at return)
 * PENDING or CONFIRMED → CANCELLED (renter, owner, or admin)
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_number", length = 20, nullable = false, unique = true)
    private String bookingNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id", nullable = false)
    private User renter;

    // Denormalized for query efficiency — avoids joining through truck
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "total_days", nullable = false)
    private Integer totalDays;

    // Price snapshots — captured at booking creation, immutable after that
    @Column(name = "price_per_day", precision = 12, scale = 2, nullable = false)
    private BigDecimal pricePerDay;

    @Column(name = "cost_per_mile", precision = 10, scale = 2, nullable = false)
    private BigDecimal costPerMile;

    // Day-based amount = pricePerDay * totalDays (known at creation)
    @Column(name = "day_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal dayAmount;

    // Odometer fields — filled by owner at handoff and return
    @Column(name = "odometer_start")
    private Integer odometerStart;

    @Column(name = "odometer_end")
    private Integer odometerEnd;

    @Column(name = "miles_driven")
    private Integer milesDriven;

    // Mileage charge = miles_driven * cost_per_mile (calculated on return)
    @Column(name = "mileage_amount", precision = 14, scale = 2)
    private BigDecimal mileageAmount;

    // Final total = day_amount + mileage_amount (set on COMPLETED)
    @Column(name = "total_amount", precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "pickup_location", length = 500)
    private String pickupLocation;

    @Column(name = "dropoff_location", length = 500)
    private String dropoffLocation;

    @Column(name = "renter_notes", columnDefinition = "TEXT")
    private String renterNotes;

    @Column(name = "owner_notes", columnDefinition = "TEXT")
    private String ownerNotes;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "handed_off_at")
    private Instant handedOffAt;

    @Column(name = "returned_at")
    private Instant returnedAt;
}
