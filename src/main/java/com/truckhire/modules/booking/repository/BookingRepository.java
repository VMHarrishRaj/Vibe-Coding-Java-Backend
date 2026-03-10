package com.truckhire.modules.booking.repository;

import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.booking.entity.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Date overlap conflict check — blocks double-booking.
     * A conflict exists when the truck has an active booking (PENDING, CONFIRMED, or ACTIVE)
     * whose date range overlaps the requested range.
     *
     * Overlap condition: existing.startDate <= requested.endDate AND existing.endDate >= requested.startDate
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.truck.id = :truckId
              AND b.status IN ('PENDING', 'CONFIRMED', 'ACTIVE')
              AND b.startDate <= :endDate
              AND b.endDate >= :startDate
            """)
    boolean existsConflictingBooking(
            @Param("truckId") UUID truckId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Conflict check excluding a specific booking (for future reschedule support).
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.truck.id = :truckId
              AND b.id != :excludeBookingId
              AND b.status IN ('PENDING', 'CONFIRMED', 'ACTIVE')
              AND b.startDate <= :endDate
              AND b.endDate >= :startDate
            """)
    boolean existsConflictingBookingExcluding(
            @Param("truckId") UUID truckId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("excludeBookingId") UUID excludeBookingId);

    /**
     * Find CONFIRMED or ACTIVE bookings for a set of truck IDs where the booking
     * is current or upcoming (endDate >= today). Used for availability enrichment
     * in the public truck search.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.truck.id IN :truckIds
              AND b.status IN ('CONFIRMED', 'ACTIVE')
              AND b.endDate >= CURRENT_DATE
            """)
    List<Booking> findCurrentOrUpcomingBookingsByTruckIds(@Param("truckIds") List<UUID> truckIds);

    /**
     * Find upcoming/active bookings for a single truck (for booked-dates + availability endpoints).
     * Includes PENDING so calendar blocks all reserved ranges, not just confirmed ones —
     * prevents two renters selecting the same dates simultaneously.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.truck.id = :truckId
              AND b.status IN ('PENDING', 'CONFIRMED', 'ACTIVE')
              AND b.endDate >= CURRENT_DATE
            ORDER BY b.startDate ASC
            """)
    List<Booking> findUpcomingBookingsByTruckId(@Param("truckId") UUID truckId);

    // Renter's bookings (paginated, newest first)
    Page<Booking> findByRenterIdOrderByCreatedAtDesc(UUID renterId, Pageable pageable);

    // Renter's bookings filtered by status
    Page<Booking> findByRenterIdAndStatusOrderByCreatedAtDesc(UUID renterId, BookingStatus status, Pageable pageable);

    // Owner's bookings (paginated, newest first)
    Page<Booking> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);

    // Owner's bookings filtered by status
    Page<Booking> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, BookingStatus status, Pageable pageable);

    /**
     * Admin: all bookings with full details, optional status filter.
     * JOIN FETCH prevents N+1 on truck and user associations.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE (:status IS NULL OR b.status = :status)
            ORDER BY b.createdAt DESC
            """)
    Page<Booking> findAllWithDetails(@Param("status") BookingStatus status, Pageable pageable);

    /**
     * Full booking detail with all associations for the detail endpoint.
     */
    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.truck t
            JOIN FETCH t.owner
            JOIN FETCH b.renter
            JOIN FETCH b.owner
            WHERE b.id = :id
            """)
    Optional<Booking> findByIdWithDetails(@Param("id") UUID id);

    // Owner dashboard: count bookings in a given status for an owner
    long countByOwnerIdAndStatus(UUID ownerId, BookingStatus status);

    // Owner dashboard: sum of total_amount for all COMPLETED bookings (earnings)
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.owner.id = :ownerId AND b.status = 'COMPLETED'")
    BigDecimal sumTotalAmountByOwnerIdAndCompleted(@Param("ownerId") UUID ownerId);

    // Get next value from the booking_seq PostgreSQL sequence
    @Query(value = "SELECT nextval('booking_seq')", nativeQuery = true)
    long nextBookingSequence();
}
