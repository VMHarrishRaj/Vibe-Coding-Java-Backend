package com.truckhire.modules.addon.entity;

import com.truckhire.common.audit.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin-managed catalog of Insurance and RSA service offerings.
 * Each record represents one insurance plan or one RSA provider.
 */
@Entity
@Table(name = "addon_service_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddonServiceType extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AddonType type;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Company or provider name (e.g. "SafeGuard Insurance Co.", "John's Towing Service") */
    @Column(length = 255)
    private String provider;

    /** Rate per unit (day, service, hour) */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal rate;

    /** PER_DAY | PER_SERVICE | PER_HOUR */
    @Column(name = "rate_unit", nullable = false, length = 20)
    @Builder.Default
    private String rateUnit = "PER_DAY";

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    /** Free-text availability window, e.g. "24/7", "Mon-Sat 8AM-8PM" (RSA) */
    @Column(length = 100)
    private String availability;

    /** Insurance only — maximum claim value */
    @Column(name = "max_coverage", precision = 12, scale = 2)
    private BigDecimal maxCoverage;

    /** ACTIVE | INACTIVE | PENDING */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
