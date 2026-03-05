package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.Truck;
import com.truckhire.modules.truck.entity.TruckStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    // Check registration uniqueness
    boolean existsByRegistrationNumber(String registrationNumber);
}
