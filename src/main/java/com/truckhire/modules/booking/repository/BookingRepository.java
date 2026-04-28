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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Date overlap conflict check — blocks double-booking.
     * Only bookings in AWAITING_APPROVAL, CONFIRMED, or ACTIVE block dates.
     * PENDING (not yet paid) is excluded — a renter who hasn't paid should not
     * prevent other renters from selecting the same dates.
     *
     * Overlap condition: existing.startDate <= requested.endDate AND existing.endDate >= requested.startDate
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.truck.id = :truckId
              AND b.status IN ('AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE')
              AND b.startDate <= :endDate
              AND b.endDate >= :startDate
            """)
    boolean existsConflictingBooking(
            @Param("truckId") UUID truckId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * Conflict check excluding a specific booking (for future reschedule support).
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.truck.id = :truckId
              AND b.id != :excludeBookingId
              AND b.status IN ('AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE')
              AND b.startDate <= :endDate
              AND b.endDate >= :startDate
            """)
    boolean existsConflictingBookingExcluding(
            @Param("truckId") UUID truckId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("excludeBookingId") UUID excludeBookingId);

    /**
     * Fleet availability: for a set of truck IDs, return all bookings whose date range
     * overlaps the given month window [monthStart, monthEnd].
     * Used by getDashboard to compute per-truck availability % for the current month.
     * Only confirmed/active bookings count as occupied — PENDING and CANCELLED do not.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.truck.id IN :truckIds
              AND b.status IN ('AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE', 'COMPLETED')
              AND b.startDate <= :monthEnd
              AND b.endDate >= :monthStart
            """)
    List<Booking> findBookingsForTrucksInMonth(
            @Param("truckIds") List<UUID> truckIds,
            @Param("monthStart") LocalDateTime monthStart,
            @Param("monthEnd") LocalDateTime monthEnd);

    /**
     * Find CONFIRMED or ACTIVE bookings for a set of truck IDs where the booking
     * is current or upcoming (endDate >= today). Used for availability enrichment
     * in the public truck search.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.truck.id IN :truckIds
              AND b.status IN ('AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE')
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
              AND b.status IN ('PENDING', 'AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE')
              AND b.endDate >= CURRENT_DATE
            ORDER BY b.startDate ASC
            """)
    List<Booking> findUpcomingBookingsByTruckId(@Param("truckId") UUID truckId);

    // Renter's bookings (paginated, newest first)
    Page<Booking> findByRenterIdOrderByCreatedAtDesc(UUID renterId, Pageable pageable);

    // Renter's bookings filtered by status
    Page<Booking> findByRenterIdAndStatusOrderByCreatedAtDesc(UUID renterId, BookingStatus status, Pageable pageable);

    // Admin: renter booking stats — [totalBookings, totalSpent, completedCount, lastBookingDate]
    @Query(value = """
            SELECT COUNT(b.id),
                   COALESCE(SUM(CASE WHEN b.status = 'COMPLETED' THEN b.total_amount ELSE 0 END), 0),
                   SUM(CASE WHEN b.status = 'COMPLETED' THEN 1 ELSE 0 END),
                   MAX(b.created_at)
            FROM bookings b
            WHERE b.renter_id = :renterId
              AND b.deleted_at IS NULL
            """, nativeQuery = true)
    List<Object[]> getRenterBookingStats(@Param("renterId") UUID renterId);

    // Owner's bookings (paginated, newest first) — excludes PENDING (unpaid) bookings.
    // Renter has not yet paid for PENDING bookings so the owner should not see them yet.
    // Date-conflict blocking still works because existsConflictingBooking includes PENDING.
    @Query("""
            SELECT b FROM Booking b
            WHERE b.owner.id = :ownerId
              AND b.status <> 'PENDING'
            ORDER BY b.createdAt DESC
            """)
    Page<Booking> findByOwnerIdExcludingPendingOrderByCreatedAtDesc(
            @Param("ownerId") UUID ownerId,
            Pageable pageable);

    // Owner's bookings filtered by explicit status (used when caller passes ?status=)
    Page<Booking> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, BookingStatus status, Pageable pageable);

    /**
     * Admin: all bookings with full details — no status filter.
     * JOIN FETCH prevents N+1 on truck and user associations.
     * countQuery uses plain JOIN (not JOIN FETCH) — Hibernate cannot derive COUNT from JOIN FETCH.
     */
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            ORDER BY b.createdAt DESC
            """,
            countQuery = "SELECT COUNT(b) FROM Booking b JOIN b.truck JOIN b.renter JOIN b.owner")
    Page<Booking> findAllWithDetails(Pageable pageable);

    /**
     * Admin: all bookings with full details — filtered by status.
     * Split from the unfiltered variant to avoid Hibernate's inability to infer
     * the type of a nullable enum bind parameter on PostgreSQL.
     * countQuery uses plain JOIN — Hibernate cannot derive COUNT from JOIN FETCH.
     */
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE b.status = :status
            ORDER BY b.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.truck JOIN b.renter JOIN b.owner
            WHERE b.status = :status
            """)
    Page<Booking> findAllWithDetailsByStatus(@Param("status") BookingStatus status, Pageable pageable);

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

    /**
     * Owner dashboard: count bookings per status in one query.
     * Returns [status, count] pairs — only statuses with at least one row are returned.
     */
    @Query("""
            SELECT b.status, COUNT(b)
            FROM Booking b
            WHERE b.owner.id = :ownerId
              AND b.status IN :statuses
            GROUP BY b.status
            """)
    List<Object[]> countByOwnerIdAndStatuses(
            @Param("ownerId") UUID ownerId,
            @Param("statuses") List<BookingStatus> statuses);

    // Owner dashboard: sum of total_amount for all COMPLETED bookings (earnings)
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.owner.id = :ownerId AND b.status = 'COMPLETED'")
    BigDecimal sumTotalAmountByOwnerIdAndCompleted(@Param("ownerId") UUID ownerId);

    // Get next value from the booking_seq PostgreSQL sequence
    @Query(value = "SELECT nextval('booking_seq')", nativeQuery = true)
    long nextBookingSequence();

    // Admin dashboard: total revenue from all COMPLETED bookings
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.status = 'COMPLETED' AND b.deletedAt IS NULL")
    BigDecimal sumTotalRevenueCompleted();

    // Admin dashboard: count by status (use with BookingStatus.ACTIVE for active bookings KPI)
    long countByStatusAndDeletedAtIsNull(BookingStatus status);

    // Admin dashboard: monthly revenue for last 12 months (COMPLETED bookings only)
    @Query(value = """
            SELECT TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                   COALESCE(SUM(total_amount), 0) AS revenue
            FROM bookings
            WHERE deleted_at IS NULL
              AND status = 'COMPLETED'
              AND created_at >= NOW() - INTERVAL '12 months'
            GROUP BY TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM')
            ORDER BY month ASC
            """, nativeQuery = true)
    List<Object[]> sumRevenueGroupedByMonth();

    /**
     * Admin: all bookings filtered by a list of statuses (e.g. CONFIRMED + AWAITING_APPROVAL for "Upcoming" tab).
     */
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE b.status IN :statuses
            ORDER BY b.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.truck JOIN b.renter JOIN b.owner
            WHERE b.status IN :statuses
            """)
    Page<Booking> findAllWithDetailsByStatuses(@Param("statuses") List<BookingStatus> statuses, Pageable pageable);

    // Admin search — free-text across bookingNumber, renter name, truck model/make
    // :q must be pre-lowercased by the caller
    // countQuery uses plain JOIN — Hibernate cannot derive COUNT from JOIN FETCH.
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            ORDER BY b.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.truck JOIN b.renter JOIN b.owner
            WHERE (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            """)
    Page<Booking> searchByKeyword(@Param("q") String q, Pageable pageable);

    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE b.status = :status
              AND (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            ORDER BY b.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.truck JOIN b.renter JOIN b.owner
            WHERE b.status = :status
              AND (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            """)
    Page<Booking> searchByKeywordAndStatus(@Param("q") String q, @Param("status") BookingStatus status, Pageable pageable);

    // Admin search — keyword + multiple statuses combined
    @Query(value = """
            SELECT b FROM Booking b
            JOIN FETCH b.truck JOIN FETCH b.renter JOIN FETCH b.owner
            WHERE b.deletedAt IS NULL
              AND b.status IN :statuses
              AND (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            """,
            countQuery = """
            SELECT COUNT(b) FROM Booking b
            JOIN b.truck JOIN b.renter JOIN b.owner
            WHERE b.deletedAt IS NULL
              AND b.status IN :statuses
              AND (LOWER(b.bookingNumber) LIKE :q
                OR LOWER(b.renter.fullname) LIKE :q
                OR LOWER(b.truck.model) LIKE :q
                OR LOWER(b.truck.make) LIKE :q)
            """)
    Page<Booking> searchByKeywordAndStatuses(
            @Param("q") String q,
            @Param("statuses") List<BookingStatus> statuses,
            Pageable pageable);

    // Admin user detail: count ACTIVE + COMPLETED bookings per truck (batch — avoids N+1)
    @Query("""
            SELECT b.truck.id, COUNT(b)
            FROM Booking b
            WHERE b.truck.id IN :truckIds
              AND b.status IN ('ACTIVE', 'COMPLETED')
              AND b.deletedAt IS NULL
            GROUP BY b.truck.id
            """)
    List<Object[]> countBookingsPerTruck(@Param("truckIds") List<UUID> truckIds);

    // Admin dashboard: monthly booking counts for last 12 months (all statuses)
    @Query(value = """
            SELECT TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                   COUNT(*) AS booking_count
            FROM bookings
            WHERE deleted_at IS NULL
              AND created_at >= NOW() - INTERVAL '12 months'
            GROUP BY TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM')
            ORDER BY month ASC
            """, nativeQuery = true)
    List<Object[]> countBookingsGroupedByMonth();
}
