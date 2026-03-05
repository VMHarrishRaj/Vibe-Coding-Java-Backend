package com.truckhire.modules.user.repository;

import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    boolean existsByPhone(String phone);

    // ── Admin list (Phase 3) — all exclude soft-deleted ──

    Page<User> findByDeletedAtIsNull(Pageable pageable);

    Page<User> findByRole_NameAndDeletedAtIsNull(String roleName, Pageable pageable);

    Page<User> findByStatusAndDeletedAtIsNull(UserStatus status, Pageable pageable);

    Page<User> findByRole_NameAndStatusAndDeletedAtIsNull(
            String roleName, UserStatus status, Pageable pageable);

    // ── Admin guard: prevent suspending the last active admin ──

    @Query("SELECT COUNT(u) FROM User u WHERE u.role.name = 'ADMIN' " +
            "AND u.status = 'ACTIVE' AND u.deletedAt IS NULL")
    long countActiveAdmins();
}
