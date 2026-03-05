package com.truckhire.modules.truck.repository;

import com.truckhire.modules.truck.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleTypeRepository extends JpaRepository<VehicleType, Integer> {

    Optional<VehicleType> findByName(String name);
}
