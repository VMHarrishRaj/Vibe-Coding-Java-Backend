package com.truckhire.modules.payment.repository;

import com.truckhire.modules.payment.entity.PlatformSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, Integer> {
    // Single-row table — always use findById(1) to get the config row
}
