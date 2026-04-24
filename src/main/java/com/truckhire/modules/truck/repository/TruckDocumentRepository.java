package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.TruckDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public interface TruckDocumentRepository extends JpaRepository<TruckDocument, UUID> {

    List<TruckDocument> findByTruckId(UUID truckId);

    // Returns all PHOTO documents for a single truck, oldest first (cover photo first).
    @Query("SELECT d FROM TruckDocument d WHERE d.truck.id = :truckId AND d.documentType.name = 'PHOTO' ORDER BY d.uploadedAt ASC")
    List<TruckDocument> findAllPhotosByTruckId(@Param("truckId") UUID truckId);

    // Returns all legal documents (RC, INSURANCE, PERMIT) for a truck — excludes photos.
    @Query("SELECT d FROM TruckDocument d WHERE d.truck.id = :truckId AND d.documentType.name IN ('RC', 'INSURANCE', 'PERMIT') ORDER BY d.uploadedAt ASC")
    List<TruckDocument> findLegalDocsByTruckId(@Param("truckId") UUID truckId);

    // Returns the first PHOTO document for each truck in the given id set.
    // Result: list of [truckId, filePath] pairs — one per truck at most.
    @Query("SELECT d.truck.id, d.filePath FROM TruckDocument d " +
           "WHERE d.truck.id IN :truckIds AND d.documentType.name = 'PHOTO' " +
           "ORDER BY d.uploadedAt ASC")
    List<Object[]> findFirstPhotoPerTruck(@Param("truckIds") List<UUID> truckIds);
}
