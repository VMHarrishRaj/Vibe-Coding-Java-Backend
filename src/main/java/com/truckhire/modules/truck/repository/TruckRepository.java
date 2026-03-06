package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.entity.TruckStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TruckRepository extends JpaRepository<Truck, UUID> {

    // Owner's trucks (non-deleted)
    Page<Truck> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

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

    // Admin list with JOIN FETCH to avoid N+1 (H5)
    @Query("SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.deletedAt IS NULL")
    Page<Truck> findAllActiveWithOwner(Pageable pageable);

    @Query("SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.status = :status AND t.deletedAt IS NULL")
    Page<Truck> findByStatusActiveWithOwnerNoOrder(@Param("status") TruckStatus status, Pageable pageable);

    @Query("SELECT t FROM Truck t JOIN FETCH t.owner JOIN FETCH t.vehicleType WHERE t.status = :status AND t.deletedAt IS NULL ORDER BY t.createdAt DESC")
    Page<Truck> findByStatusActiveWithOwner(@Param("status") TruckStatus status, Pageable pageable);
}
