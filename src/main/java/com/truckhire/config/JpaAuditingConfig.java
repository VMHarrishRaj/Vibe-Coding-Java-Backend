package com.truckhire.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA Auditing — makes @CreatedDate and @LastModifiedDate work.
 *
 * Without this config, Spring ignores the auditing annotations
 * in BaseAuditEntity and timestamps would remain null.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    // No methods needed — the annotations do all the work.
}
