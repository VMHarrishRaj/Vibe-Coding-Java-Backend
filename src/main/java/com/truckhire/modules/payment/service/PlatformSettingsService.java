package com.truckhire.modules.payment.service;

import com.truckhire.modules.payment.config.PaymentConfig;
import com.truckhire.modules.payment.dto.PlatformSettingsRequest;
import com.truckhire.modules.payment.dto.PlatformSettingsResponse;
import com.truckhire.modules.payment.entity.PlatformSettings;
import com.truckhire.modules.payment.enums.PaymentGateway;
import com.truckhire.modules.payment.repository.PlatformSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * PlatformSettingsService — manages the admin-configurable platform settings row.
 *
 * The platform_settings table always has exactly one row (id=1, seeded by V16 migration).
 * This service reads/updates that row.
 *
 * Why single-row instead of key-value pairs?
 * Structured: the entity has typed fields, validation is at the Java layer.
 * Simple: one findById(1) to get all settings. No dynamic key lookups.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingsRepository repository;
    private final PaymentConfig paymentConfig;

    @Transactional(readOnly = true)
    public PlatformSettings getSettings() {
        return repository.findById(1)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_settings row missing — V16 migration may not have run"));
    }

    @Transactional
    public PlatformSettingsResponse updateSettings(UUID adminId, PlatformSettingsRequest request) {
        PlatformSettings settings = getSettings();
        settings.setActiveGateway(PaymentGateway.valueOf(request.getActiveGateway()));
        settings.setActiveCurrency(request.getActiveCurrency());
        settings.setPlatformFeePercent(request.getPlatformFeePercent());
        settings.setUpdatedAt(Instant.now());
        settings.setUpdatedBy(adminId);
        repository.save(settings);
        return toResponse(settings);
    }

    @Transactional(readOnly = true)
    public PlatformSettingsResponse getSettingsResponse() {
        return toResponse(getSettings());
    }

    private PlatformSettingsResponse toResponse(PlatformSettings s) {
        String publicKey = s.getActiveGateway() == PaymentGateway.RAZORPAY
                ? paymentConfig.getRazorpay().getKeyId()
                : paymentConfig.getStripe().getPublishableKey();

        return PlatformSettingsResponse.builder()
                .activeGateway(s.getActiveGateway().name())
                .activeCurrency(s.getActiveCurrency())
                .platformFeePercent(s.getPlatformFeePercent())
                .publicKey(publicKey)
                .updatedAt(s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null)
                .build();
    }
}
