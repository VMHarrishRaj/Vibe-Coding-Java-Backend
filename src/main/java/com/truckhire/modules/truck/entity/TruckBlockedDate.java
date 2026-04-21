package com.truckhire.modules.truck.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A single date blocked by the truck owner.
 * Blacklist model — absence = available. One row per truck per blocked date.
 */
@Entity
@Table(
    name = "truck_blocked_dates",
    uniqueConstraints = @UniqueConstraint(columnNames = {"truck_id", "blocked_date"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TruckBlockedDate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "truck_id", nullable = false)
    private Truck truck;

    @Column(name = "blocked_date", nullable = false)
    private LocalDate blockedDate;
}
