package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.PickupLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PickupLocationRepository extends JpaRepository<PickupLocation, UUID> {

    // Batch load for list/search responses — avoids N+1 across a page of trucks
    List<PickupLocation> findByTruckIdIn(List<UUID> truckIds);

    // Used during updateTruck to replace all locations atomically
    void deleteByTruckId(UUID truckId);
}
