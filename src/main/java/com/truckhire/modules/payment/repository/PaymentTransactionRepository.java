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

    // Admin dashboard: total platform revenue = sum of all PAID CHARGE + MILEAGE_TOPUP transactions.
    // Uses payment_transactions as single source of truth — matches exactly what the invoice screen shows.
    // Intentionally NOT filtered by booking status: a booking can be ACTIVE with payment already captured.
    @Query("""
            SELECT COALESCE(SUM(pt.amount), 0)
            FROM PaymentTransaction pt
            WHERE pt.type IN ('CHARGE', 'MILEAGE_TOPUP')
              AND pt.status = 'SUCCEEDED'
            """)
    BigDecimal sumTotalPlatformRevenue();

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
}
