package com.truckhire.modules.user.repository;

import com.truckhire.modules.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for the roles lookup table.
 *
 * Spring Data JPA auto-generates the implementation at runtime.
 * You just define the interface — Spring writes the SQL.
 *
 * findByName("OWNER") → SELECT * FROM roles WHERE name = 'OWNER'
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByName(String name);
}
