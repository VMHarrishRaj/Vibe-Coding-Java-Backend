package com.truckhire.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base class for ALL JPA entities in the system.
 *
 * WHY THIS EXISTS:
 * Every table needs created_at, updated_at, and deleted_at columns.
 * Instead of repeating these in every entity, we define them ONCE here.
 * Each entity extends this class and inherits these fields.
 *
 * HOW IT WORKS:
 * - @MappedSuperclass: JPA knows this is NOT a table, but its fields
 *   should be included in child entity tables.
 * - @EntityListeners(AuditingEntityListener.class): Spring automatically
 *   sets createdAt and updatedAt on save/update.
 * - @CreatedDate: Auto-set to current time on INSERT
 * - @LastModifiedDate: Auto-set to current time on UPDATE
 * - deletedAt: Manual soft-delete (set when "deleting", filter in queries)
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Soft delete timestamp.
     * When set, the entity is considered "deleted" but remains in the database.
     * All queries should filter: WHERE deleted_at IS NULL
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ── Soft delete helpers ──

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }
}
