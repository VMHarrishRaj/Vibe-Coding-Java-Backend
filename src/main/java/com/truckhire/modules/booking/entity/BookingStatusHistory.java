package com.truckhire.modules.booking.entity;

import com.truckhire.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Records every status transition for a booking.
 * Provides a full audit trail: who changed status, when, and why.
 */
@Entity
@Table(name = "booking_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private BookingStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "changed_at", nullable = false)
    @Builder.Default
    private Instant changedAt = Instant.now();
}
