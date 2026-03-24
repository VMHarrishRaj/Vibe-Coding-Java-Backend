package com.truckhire.modules.payment.entity;

import com.truckhire.modules.payment.enums.PaymentGateway;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PlatformSettings Entity — maps to the 'platform_settings' table.
 *
 * Single-row config table (id=1 always). Stores admin-configurable settings
 * that should take effect immediately without a redeploy.
 *
 * WHY NOT application.yml?
 * Active gateway and currency need to change at demo time without touching
 * code or env vars. Storing in DB means admin can flip them via API in seconds.
 *
 * Does NOT extend BaseAuditEntity — this is a config table with manual
 * updated_at and updated_by fields, not a domain entity with soft-delete.
 */
@Entity
@Table(name = "platform_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_gateway", length = 20, nullable = false)
    private PaymentGateway activeGateway;

    @Column(name = "active_currency", length = 3, nullable = false)
    private String activeCurrency;

    @Column(name = "platform_fee_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal platformFeePercent;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
