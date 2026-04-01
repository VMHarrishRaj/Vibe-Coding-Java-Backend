package com.truckhire.modules.user.repository;

import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the users table.
 *
 * DESIGN DECISION (Soft-Delete Filtering):
 * All list/find methods that return user data to the API
 * MUST filter out soft-deleted users (deleted_at IS NULL).
 * The only exception is findById (used internally for admin operations
 * where we might need to see deleted records).
 *
 * Spring Data JPA derives SQL from method names:
 * findByEmail("x@y.com") → SELECT * FROM users WHERE email = 'x@y.com'
 * existsByEmail("x@y.com") → SELECT EXISTS(...)
 * findByDeletedAtIsNull → ... WHERE deleted_at IS NULL
 * Role_Name → JOIN roles ON ... WHERE roles.name = ?
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // ── Auth (Phase 2) ──

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    // ── Admin list (Phase 3) — all exclude soft-deleted ──

    Page<User> findByDeletedAtIsNull(Pageable pageable);

    Page<User> findByRole_NameAndDeletedAtIsNull(String roleName, Pageable pageable);

    Page<User> findByStatusAndDeletedAtIsNull(UserStatus status, Pageable pageable);

    Page<User> findByRole_NameAndStatusAndDeletedAtIsNull(
            String roleName, UserStatus status, Pageable pageable);

    // ── Admin search — free-text across fullname, email, phone ──
    // :q must be pre-lowercased by the caller (avoids Hibernate bytea issue with LOWER on nullable)

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (LOWER(u.fullname) LIKE :q OR LOWER(u.email) LIKE :q OR u.phone LIKE :q)
            """)
    Page<User> searchByKeyword(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role.name = :role
              AND (LOWER(u.fullname) LIKE :q OR LOWER(u.email) LIKE :q OR u.phone LIKE :q)
            """)
    Page<User> searchByKeywordAndRole(@Param("q") String q, @Param("role") String role, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.status = :status
              AND (LOWER(u.fullname) LIKE :q OR LOWER(u.email) LIKE :q OR u.phone LIKE :q)
            """)
    Page<User> searchByKeywordAndStatus(@Param("q") String q, @Param("status") UserStatus status, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role.name = :role
              AND u.status = :status
              AND (LOWER(u.fullname) LIKE :q OR LOWER(u.email) LIKE :q OR u.phone LIKE :q)
            """)
    Page<User> searchByKeywordAndRoleAndStatus(@Param("q") String q, @Param("role") String role, @Param("status") UserStatus status, Pageable pageable);

    // ── Stripe Connect filter (OWNER role only) ──

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role.name = 'OWNER'
              AND u.stripeAccountId IS NOT NULL
            """)
    Page<User> findOwnersWithStripeConnected(Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND u.role.name = 'OWNER'
              AND u.stripeAccountId IS NULL
            """)
    Page<User> findOwnersWithoutStripeConnected(Pageable pageable);

    // ── Admin guard: prevent suspending the last active admin ──

    @Query("SELECT COUNT(u) FROM User u WHERE u.role.name = 'ADMIN' " +
            "AND u.status = 'ACTIVE' AND u.deletedAt IS NULL")
    long countActiveAdmins();

    // Admin dashboard: count non-deleted users by role name
    long countByRole_NameAndDeletedAtIsNull(String roleName);

    // Admin dashboard: count active (non-deleted) users by role name and status
    long countByRole_NameAndStatusAndDeletedAtIsNull(String roleName, UserStatus status);
}
