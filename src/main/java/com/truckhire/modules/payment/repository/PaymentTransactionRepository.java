package com.truckhire.modules.payment.repository;

import com.truckhire.modules.payment.entity.PaymentTransaction;
import com.truckhire.modules.payment.enums.PaymentStatus;
import com.truckhire.modules.payment.enums.PaymentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    Optional<PaymentTransaction> findByBookingIdAndTypeAndStatus(
            UUID bookingId, PaymentType type, PaymentStatus status);

    Optional<PaymentTransaction> findFirstByBookingIdAndTypeOrderByCreatedAtDesc(
            UUID bookingId, PaymentType type);

    Optional<PaymentTransaction> findFirstByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    boolean existsByBookingIdAndTypeAndStatusIn(UUID bookingId, PaymentType type, java.util.List<PaymentStatus> statuses);

    boolean existsByBookingIdAndType(UUID bookingId, PaymentType type);

    /**
     * Admin payments list — CHARGE + MILEAGE_TOPUP transactions, with booking + renter eagerly loaded.
     * Each row is one invoice entry: day-rate charge OR mileage charge.
     * Supports optional free-text search on invoiceNumber or bookingNumber.
     */
    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
            ORDER BY t.createdAt DESC
            """,
            countQuery = "SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP') AND t.status NOT IN ('CANCELLED', 'REFUNDED')")
    Page<PaymentTransaction> findAllChargesWithDetails(Pageable pageable);

    // Admin: all CHARGE/MILEAGE_TOPUP transactions for a specific renter
    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter r
            WHERE r.id = :renterId
              AND t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t
            JOIN t.booking b JOIN b.renter r
            WHERE r.id = :renterId
              AND t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
            """)
    Page<PaymentTransaction> findChargesByRenterId(@Param("renterId") UUID renterId, Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            """)
    Page<PaymentTransaction> searchChargesWithDetails(@Param("q") String q, Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = :status
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = :status
            """)
    Page<PaymentTransaction> findChargesByStatus(
            @Param("status") PaymentStatus status, Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = :status
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = :status
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            """)
    Page<PaymentTransaction> searchChargesByStatus(
            @Param("q") String q, @Param("status") PaymentStatus status, Pageable pageable);

    // Get next invoice sequence value
    @Query(value = "SELECT nextval('invoice_seq')", nativeQuery = true)
    long nextInvoiceSequence();

    // RENTER payment history — CHARGE + MILEAGE_TOPUP + REFUND transactions for bookings they rented
    // CANCELLED charges excluded (no money moved); REFUNDED charges included so renter sees refund history
    @Query("""
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.truck
            WHERE b.renter.id = :userId
              AND t.type IN ('CHARGE', 'MILEAGE_TOPUP', 'REFUND')
              AND t.status != 'CANCELLED'
            ORDER BY t.createdAt DESC
            """)
    org.springframework.data.domain.Page<PaymentTransaction> findRenterPaymentHistory(
            @Param("userId") java.util.UUID userId,
            org.springframework.data.domain.Pageable pageable);

    // All renter transactions (non-paginated) for booking-grouped response.
    // Ordered by booking creation DESC so groups surface in the same order as a
    // flat newest-first list. CANCELLED excluded — no money moved.
    @Query("""
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.truck
            WHERE b.renter.id = :userId
              AND t.type IN ('CHARGE', 'MILEAGE_TOPUP', 'REFUND')
              AND t.status != 'CANCELLED'
            ORDER BY b.createdAt DESC, t.createdAt ASC
            """)
    List<PaymentTransaction> findRenterTransactionsForGrouping(
            @Param("userId") java.util.UUID userId);

    // OWNER earnings history — PAYOUT transactions for bookings they own
    @Query("""
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.truck
            WHERE b.owner.id = :userId
              AND t.type = 'PAYOUT'
            ORDER BY t.createdAt DESC
            """)
    org.springframework.data.domain.Page<PaymentTransaction> findOwnerPayoutHistory(
            @Param("userId") java.util.UUID userId,
            org.springframework.data.domain.Pageable pageable);

    // Owner dashboard: net earnings = sum of owner_amount from PAYOUT transactions (PAID_OUT or PAYOUT_PENDING)
    // Includes PAYOUT_PENDING so bookings where the owner has no linked account still count (money is owed to them)
    @Query("""
            SELECT COALESCE(SUM(pt.ownerAmount), 0)
            FROM PaymentTransaction pt
            WHERE pt.booking.owner.id = :ownerId
              AND pt.type = 'PAYOUT'
              AND pt.status IN ('PAID_OUT', 'PAYOUT_PENDING')
            """)
    BigDecimal sumOwnerEarnings(@Param("ownerId") UUID ownerId);

    // Admin dashboard: total platform revenue = sum of all CHARGE + MILEAGE_TOPUP transactions (both paid and pending).
    // Intentionally NOT filtered by booking status: a booking can be ACTIVE with payment already captured.
    @Query("""
            SELECT COALESCE(SUM(pt.amount), 0)
            FROM PaymentTransaction pt
            WHERE pt.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND pt.status IN ('SUCCEEDED', 'PENDING')
            """)
    BigDecimal sumTotalPlatformRevenue();

    // Admin dashboard: monthly revenue for last 12 months — same source as sumTotalPlatformRevenue().
    // Returns [month (YYYY-MM), revenue] pairs ordered oldest first.
    @Query(value = """
            SELECT TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                   COALESCE(SUM(amount), 0) AS revenue
            FROM payment_transactions
            WHERE type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND status IN ('SUCCEEDED', 'PENDING')
              AND created_at >= NOW() - INTERVAL '12 months'
            GROUP BY TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY-MM')
            ORDER BY month ASC
            """, nativeQuery = true)
    List<Object[]> sumRevenueGroupedByMonth();

    // ── Grouped invoice view (GET /admin/payments/by-booking) ──
    // Returns only CHARGE transactions (one per booking). Mileage is fetched separately via findMileageByBookingIds.

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type = 'CHARGE'
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
            ORDER BY t.createdAt DESC
            """,
            countQuery = "SELECT COUNT(t) FROM PaymentTransaction t WHERE t.type = 'CHARGE' AND t.status NOT IN ('CANCELLED', 'REFUNDED')")
    Page<PaymentTransaction> findAllChargeOnlyWithDetails(Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type = 'CHARGE'
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b
            WHERE t.type = 'CHARGE'
              AND t.status NOT IN ('CANCELLED', 'REFUNDED')
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            """)
    Page<PaymentTransaction> searchChargeOnlyWithDetails(@Param("q") String q, Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type = 'CHARGE'
              AND t.status = :status
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t WHERE t.type = 'CHARGE' AND t.status = :status
            """)
    Page<PaymentTransaction> findChargeOnlyByStatus(
            @Param("status") PaymentStatus status, Pageable pageable);

    @Query(value = """
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            JOIN FETCH b.renter
            WHERE t.type = 'CHARGE'
              AND t.status = :status
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM PaymentTransaction t JOIN t.booking b
            WHERE t.type = 'CHARGE'
              AND t.status = :status
              AND (:q IS NULL OR LOWER(t.invoiceNumber) LIKE :q OR LOWER(b.bookingNumber) LIKE :q)
            """)
    Page<PaymentTransaction> searchChargeOnlyByStatus(
            @Param("q") String q, @Param("status") PaymentStatus status, Pageable pageable);

    // Batch fetch mileage transactions for a set of bookings — used to zip into grouped invoice rows.
    // JOIN FETCH to avoid lazy-load on booking when building the response.
    @Query("""
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            WHERE b.id IN :bookingIds AND t.type = 'MILEAGE_TOPUP'
            """)
    List<PaymentTransaction> findMileageByBookingIds(@Param("bookingIds") List<UUID> bookingIds);

    // Single-booking mileage lookup — used in invoice detail to fetch the paired MILEAGE_TOPUP for a CHARGE.
    @Query("""
            SELECT t FROM PaymentTransaction t
            JOIN FETCH t.booking b
            WHERE b.id = :bookingId AND t.type = 'MILEAGE_TOPUP'
            """)
    Optional<PaymentTransaction> findMileageByBookingId(@Param("bookingId") UUID bookingId);

    // ── by-booking page widget stats ──

    /** Total amount of all SUCCEEDED CHARGE + MILEAGE_TOPUP (money actually captured). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM PaymentTransaction t
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = 'SUCCEEDED'
            """)
    BigDecimal sumTotalPaidCharges();

    /** Sum of PENDING CHARGE amounts (bookings not yet paid). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM PaymentTransaction t
            WHERE t.type = 'CHARGE'
              AND t.status = 'PENDING'
            """)
    BigDecimal sumPendingCharges();

    /** Sum of platform fee on all SUCCEEDED transactions. */
    @Query("""
            SELECT COALESCE(SUM(t.platformFee), 0)
            FROM PaymentTransaction t
            WHERE t.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND t.status = 'SUCCEEDED'
            """)
    BigDecimal sumPlatformFees();

    /** Sum of owner payouts that have been PAID_OUT. */
    @Query("""
            SELECT COALESCE(SUM(t.ownerAmount), 0)
            FROM PaymentTransaction t
            WHERE t.type = 'PAYOUT'
              AND t.status = 'PAID_OUT'
            """)
    BigDecimal sumSettledOwnerPayouts();

    /** Owner dashboard: total ownerAmount successfully paid out (PAID_OUT) for a specific owner. */
    @Query("""
            SELECT COALESCE(SUM(t.ownerAmount), 0)
            FROM PaymentTransaction t
            WHERE t.booking.owner.id = :ownerId
              AND t.type = 'PAYOUT'
              AND t.status = 'PAID_OUT'
            """)
    BigDecimal sumOwnerCompletedPayouts(@Param("ownerId") UUID ownerId);

    /**
     * Owner dashboard: sum of ownerAmount still awaiting payout (PAYOUT_PENDING).
     * Money is owed to the owner but hasn't transferred yet (no gateway account linked,
     * or transfer failed and is pending retry).
     */
    @Query("""
            SELECT COALESCE(SUM(t.ownerAmount), 0)
            FROM PaymentTransaction t
            WHERE t.booking.owner.id = :ownerId
              AND t.type = 'PAYOUT'
              AND t.status = 'PAYOUT_PENDING'
            """)
    BigDecimal sumOwnerPendingPayouts(@Param("ownerId") UUID ownerId);

    /**
     * Owner dashboard: monthly earnings for the last 12 months.
     * Sums ownerAmount from PAYOUT transactions (PAID_OUT + PAYOUT_PENDING)
     * grouped by month. Returns [month (YYYY-MM), revenue] pairs, oldest first.
     * Months with no activity are not returned — caller handles the gap.
     */
    @Query(value = """
            SELECT TO_CHAR(pt.created_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                   COALESCE(SUM(pt.owner_amount), 0) AS revenue
            FROM payment_transactions pt
            JOIN bookings b ON b.id = pt.booking_id
            WHERE b.owner_id = :ownerId
              AND pt.type = 'PAYOUT'
              AND pt.status IN ('PAID_OUT', 'PAYOUT_PENDING')
              AND pt.created_at >= NOW() - INTERVAL '12 months'
            GROUP BY TO_CHAR(pt.created_at AT TIME ZONE 'UTC', 'YYYY-MM')
            ORDER BY month ASC
            """, nativeQuery = true)
    List<Object[]> sumOwnerRevenueGroupedByMonth(@Param("ownerId") UUID ownerId);

    /** Most recent PAID_OUT payout date for an owner — used in admin owner detail stats. */
    @Query("""
            SELECT t.createdAt
            FROM PaymentTransaction t
            WHERE t.booking.owner.id = :ownerId
              AND t.type = 'PAYOUT'
              AND t.status = 'PAID_OUT'
            ORDER BY t.createdAt DESC
            LIMIT 1
            """)
    Optional<java.time.Instant> findLastPayoutDateForOwner(@Param("ownerId") UUID ownerId);
}
