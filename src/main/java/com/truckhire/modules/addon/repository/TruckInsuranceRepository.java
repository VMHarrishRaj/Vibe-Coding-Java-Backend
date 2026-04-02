package com.truckhire.modules.addon.repository;

import com.truckhire.modules.addon.entity.TruckInsurance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TruckInsuranceRepository extends JpaRepository<TruckInsurance, UUID> {

    // Get all active (non-deleted) insurance records for a truck
    List<TruckInsurance> findByTruckIdAndDeletedAtIsNull(UUID truckId);

    // Check if any active insurance exists for a truck (used to maintain trucks.insured flag)
    boolean existsByTruckIdAndDeletedAtIsNull(UUID truckId);

    // By id, non-deleted
    Optional<TruckInsurance> findByIdAndDeletedAtIsNull(UUID id);

    // Guard: ensure insurance record belongs to owner's truck before deletion
    Optional<TruckInsurance> findByIdAndTruckIdAndDeletedAtIsNull(UUID id, UUID truckId);
}
