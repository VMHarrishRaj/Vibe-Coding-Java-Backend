package com.truckhire.modules.addon.repository;

import com.truckhire.modules.addon.entity.AddonServiceType;
import com.truckhire.modules.addon.entity.AddonType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AddonServiceTypeRepository extends JpaRepository<AddonServiceType, UUID> {

    // Admin: all non-deleted
    Page<AddonServiceType> findByDeletedAtIsNull(Pageable pageable);

    // Admin: filter by type
    Page<AddonServiceType> findByTypeAndDeletedAtIsNull(AddonType type, Pageable pageable);

    // Renter: active RSA options to choose from
    List<AddonServiceType> findByTypeAndStatusAndDeletedAtIsNull(AddonType type, String status);

    // By id, non-deleted
    Optional<AddonServiceType> findByIdAndDeletedAtIsNull(UUID id);
}
