package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.entity.TruckStatus;
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
public interface TruckRepository extends JpaRepository<Truck, UUID> {

    // Owner's trucks (non-deleted)
    Page<Truck> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

    // Admin user detail: all non-deleted trucks for an owner (no pagination)
    List<Truck> findByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    // Public search: only APPROVED trucks
    Page<Truck> findByStatusAndDeletedAtIsNull(TruckStatus status, Pageable pageable);

    // Public search: APPROVED + filtered by city
    Page<Truck> findByStatusAndLocationCityIgnoreCaseAndDeletedAtIsNull(
            TruckStatus status, String locationCity, Pageable pageable);

    // Public search: APPROVED + filtered by vehicle type
    Page<Truck> findByStatusAndVehicleType_NameAndDeletedAtIsNull(
            TruckStatus status, String vehicleTypeName, Pageable pageable);

    // Admin: all trucks with specific status
    Page<Truck> findByDeletedAtIsNull(Pageable pageable);

    // Admin: pending trucks
    Page<Truck> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            TruckStatus status, Pageable pageable);

    // By id with soft-delete check
    Optional<Truck> findByIdAndDeletedAtIsNull(UUID id);

    // Check registration uniqueness (all, including soft-deleted)
    boolean existsByRegistrationNumber(String registrationNumber);

    // Check registration uniqueness for active (non-deleted) trucks only
    boolean existsByRegistrationNumberAndDeletedAtIsNull(String registrationNumber);

    // For KYC rejection cascade: find owner's APPROVED trucks
    List<Truck> findByOwnerIdAndStatusAndDeletedAtIsNull(UUID ownerId, TruckStatus status);

    // ── Enhanced public search (Phase 5) ──
    // Returns APPROVED, INACTIVE, and PENDING_APPROVAL trucks (not REJECTED).
    // Availability enrichment (AVAILABLE / RENTED / UNAVAILABLE) is done in
    // the service layer after this query runs — one extra query per page, not N+1.
    // All filter params are optional — passing null skips that condition.
    // Sorting is handled by the Pageable passed from the service layer.
    // :cityLower must be pre-lowercased by the caller (or null to skip filter).
    // Avoids LOWER(:city) on a nullable bind param — Hibernate 6 binds null as
    // bytea on PostgreSQL, causing "function lower(bytea) does not exist".
    //
    // Two variants: without date filter (when no dates provided) and with date filter.
    // This avoids PostgreSQL's inability to infer the type of a nullable LocalDate
    // bind parameter used in a ":param IS NULL OR ..." guard ("could not determine
    // data type of parameter $N"). Splitting into two methods eliminates the guard.
    @Query("""
            SELECT t FROM Truck t
            JOIN FETCH t.owner
            JOIN FETCH t.vehicleType
            WHERE t.status IN ('APPROVED', 'INACTIVE', 'PENDING_APPROVAL')
              AND t.deletedAt IS NULL
              AND (:cityLower IS NULL OR LOWER(t.locationCity) = :cityLower)
              AND (:vehicleType IS NULL OR t.vehicleType.name = :vehicleType)
              AND (:minPrice IS NULL OR t.pricePerDay >= :minPrice)
              AND (:maxPrice IS NULL OR t.pricePerDay <= :maxPrice)
              AND (:minCapacity IS NULL OR t.capacityTons >= :minCapacity)
            """)
    Page<Truck> searchPublicTrucks(
            @Param("cityLower") String cityLower,
            @Param("vehicleType") String vehicleType,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("minCapacity") Integer minCapacity,
            Pageable pageable);

    // Date-filtered variant — only called when both availableFrom and availableTo are non-null.
    // Booking is resolved via JPA persistence context — no Java import needed here.
    @Query("""
            SELECT t FROM Truck t
            JOIN FETCH t.owner
            JOIN FETCH t.vehicleType
            WHERE t.status IN ('APPROVED', 'INACTIVE', 'PENDING_APPROVAL')
              AND t.deletedAt IS NULL
              AND (:cityLower IS NULL OR LOWER(t.locationCity) = :cityLower)
              AND (:vehicleType IS NULL OR t.vehicleType.name = :vehicleType)
              AND (:minPrice IS NULL OR t.pricePerDay >= :minPrice)
              AND (:maxPrice IS NULL OR t.pricePerDay <= :maxPrice)
              AND (:minCapacity IS NULL OR t.capacityTons >= :minCapacity)
              AND t.id NOT IN (
                  SELECT b.truck.id FROM Booking b
                  WHERE b.status IN ('PENDING', 'AWAITING_APPROVAL', 'CONFIRMED', 'ACTIVE')
                    AND b.startDate <= :availableTo
                    AND b.endDate >= :availableFrom
              )
            """)
    Page<Truck> searchPublicTrucksWithDates(
            @Param("cityLower") String cityLower,
            @Param("vehicleType") String vehicleType,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("minCapacity") Integer minCapacity,
            @Param("availableFrom") LocalDate availableFrom,
            @Param("availableTo") LocalDate availableTo,
            Pageable pageable);

    // ── Owner dashboard counts (M7) ──
    // Returns [status, count] pairs for all non-deleted trucks owned by ownerId.
    // Using GROUP BY instead of N separate count queries — one DB round-trip.
    @Query("SELECT t.status, COUNT(t) FROM Truck t WHERE t.owner.id = :ownerId AND t.deletedAt IS NULL GROUP BY t.status")
    List<Object[]> countTrucksByStatusForOwner(@Param("ownerId") UUID ownerId);

    // Admin list with JOIN FETCH to avoid N+1 (H5)
    // countQuery uses plain JOIN — Hibernate cannot derive COUNT from JOIN FETCH.
    @Query(value = "SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.deletedAt IS NULL",
            countQuery = "SELECT COUNT(t) FROM Truck t WHERE t.deletedAt IS NULL")
    Page<Truck> findAllActiveWithOwner(Pageable pageable);

    // Admin search — free-text across registrationNumber, model, make, owner fullname
    // :q must be pre-lowercased by the caller
    // countQuery uses plain JOIN — Hibernate cannot derive COUNT from JOIN FETCH.
    @Query(value = """
            SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType
            WHERE t.deletedAt IS NULL
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """,
            countQuery = """
            SELECT COUNT(t) FROM Truck t
            WHERE t.deletedAt IS NULL
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """)
    Page<Truck> searchByKeyword(@Param("q") String q, Pageable pageable);

    @Query(value = """
            SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """,
            countQuery = """
            SELECT COUNT(t) FROM Truck t
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """)
    Page<Truck> searchByKeywordAndStatus(@Param("q") String q, @Param("status") TruckStatus status, Pageable pageable);

    @Query(value = "SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.status = :status AND t.deletedAt IS NULL",
            countQuery = "SELECT COUNT(t) FROM Truck t WHERE t.status = :status AND t.deletedAt IS NULL")
    Page<Truck> findByStatusActiveWithOwnerNoOrder(@Param("status") TruckStatus status, Pageable pageable);

    // ── Admin vehicleType-aware queries (SCRUM-68 fix applied to admin path) ──
    // Nullable-guard pattern (:param IS NULL OR ...) is unreliable in Hibernate 6 /
    // PostgreSQL for relationship fields (t.vehicleType.name). Each method below
    // hard-codes exactly which filters apply — no nullable guards for vehicleType.

    // vehicleType only (no status, no keyword) — derived query is safe here because
    // Spring Data resolves VehicleType.name via the JOIN implicitly.
    Page<Truck> findByVehicleTypeNameAndDeletedAtIsNull(String vehicleTypeName, Pageable pageable);

    // vehicleType + status (no keyword)
    @Query(value = """
            SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND t.vehicleType.name = :vehicleType
            """,
            countQuery = """
            SELECT COUNT(t) FROM Truck t JOIN t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND t.vehicleType.name = :vehicleType
            """)
    Page<Truck> findByStatusAndVehicleType(
            @Param("status") TruckStatus status,
            @Param("vehicleType") String vehicleType,
            Pageable pageable);

    // vehicleType + keyword (no status)
    @Query(value = """
            SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.vehicleType.name = :vehicleType
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """,
            countQuery = """
            SELECT COUNT(t) FROM Truck t JOIN t.owner JOIN t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.vehicleType.name = :vehicleType
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """)
    Page<Truck> searchByKeywordAndVehicleType(
            @Param("q") String q,
            @Param("vehicleType") String vehicleType,
            Pageable pageable);

    // vehicleType + status + keyword (all three filters)
    @Query(value = """
            SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND t.vehicleType.name = :vehicleType
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """,
            countQuery = """
            SELECT COUNT(t) FROM Truck t JOIN t.owner JOIN t.vehicleType
            WHERE t.deletedAt IS NULL
              AND t.status = :status
              AND t.vehicleType.name = :vehicleType
              AND (LOWER(t.registrationNumber) LIKE :q
                OR LOWER(t.model) LIKE :q
                OR LOWER(t.make) LIKE :q
                OR LOWER(t.owner.fullname) LIKE :q)
            """)
    Page<Truck> searchByKeywordAndStatusAndVehicleType(
            @Param("q") String q,
            @Param("status") TruckStatus status,
            @Param("vehicleType") String vehicleType,
            Pageable pageable);

    @Query(value = "SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.status = :status AND t.deletedAt IS NULL ORDER BY t.createdAt DESC",
            countQuery = "SELECT COUNT(t) FROM Truck t WHERE t.status = :status AND t.deletedAt IS NULL")
    Page<Truck> findByStatusActiveWithOwner(@Param("status") TruckStatus status, Pageable pageable);

    // Admin dashboard: total non-deleted trucks (all statuses)
    long countByDeletedAtIsNull();

    // Admin user list: vehicle count per owner (single query, avoids N+1)
    long countByOwnerIdAndDeletedAtIsNull(UUID ownerId);

    // Admin user list: batch vehicle counts for a list of owner IDs — one query for the whole page
    @Query("SELECT t.owner.id, COUNT(t) FROM Truck t WHERE t.owner.id IN :ownerIds AND t.deletedAt IS NULL GROUP BY t.owner.id")
    List<Object[]> countTrucksByOwnerIds(@Param("ownerIds") List<UUID> ownerIds);

    // Admin dashboard: count non-deleted trucks by a specific status
    long countByStatusAndDeletedAtIsNull(TruckStatus status);

    // Admin dashboard: count APPROVED trucks that have an ACTIVE booking today (rented right now)
    @Query("""
            SELECT COUNT(DISTINCT t) FROM Truck t
            WHERE t.status = 'APPROVED'
              AND t.deletedAt IS NULL
              AND EXISTS (
                  SELECT 1 FROM Booking b
                  WHERE b.truck.id = t.id
                    AND b.status = 'ACTIVE'
                    AND b.deletedAt IS NULL
              )
            """)
    long countRentedTrucks();
}
