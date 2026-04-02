package com.truckhire.modules.addon.repository;

import com.truckhire.modules.addon.entity.TruckEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TruckEquipmentRepository extends JpaRepository<TruckEquipment, UUID> {

    // Owner: list equipment for a specific truck
    List<TruckEquipment> findByTruckIdAndDeletedAtIsNull(UUID truckId);

    // Booking validation: check equipment belongs to the truck being booked
    List<TruckEquipment> findByIdInAndTruckIdAndDeletedAtIsNull(List<UUID> ids, UUID truckId);

    // By id, non-deleted
    Optional<TruckEquipment> findByIdAndDeletedAtIsNull(UUID id);

    // Owner guard: ensure equipment belongs to owner's truck
    Optional<TruckEquipment> findByIdAndTruckIdAndDeletedAtIsNull(UUID id, UUID truckId);
}
