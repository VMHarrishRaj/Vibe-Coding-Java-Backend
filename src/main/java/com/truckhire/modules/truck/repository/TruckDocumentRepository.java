package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.TruckDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TruckDocumentRepository extends JpaRepository<TruckDocument, UUID> {

    List<TruckDocument> findByTruckId(UUID truckId);
}
