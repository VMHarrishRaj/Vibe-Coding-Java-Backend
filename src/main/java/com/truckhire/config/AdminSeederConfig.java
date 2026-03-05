package com.truckhire.config;

import com.truckhire.modules.user.repository.UserRepository;
import com.truckhire.modules.user.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Admin Seeder Config — startup verification for the default admin account.
 *
 * The actual admin user is inserted by Flyway migration
 * V3__seed_default_admin.sql.
 * This config runs AFTER Flyway and verifies the admin exists in the database.
 *
 * PURPOSE:
 * - Logs a clear confirmation that the platform has an admin user
 * - Warns loudly if no admin exists (safety net for misconfigured environments)
 *
 * This does NOT create the admin — Flyway does. This is purely a verification
 * log.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminSeederConfig {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Bean
    public CommandLineRunner verifyAdminExists() {
        return args -> {
            boolean adminRoleExists = roleRepository.findByName("ADMIN").isPresent();

            if (!adminRoleExists) {
                log.error("═══════════════════════════════════════════════");
                log.error("  CRITICAL: ADMIN role not found in database!");
                log.error("  Check Flyway migration V1__create_lookup_tables.sql");
                log.error("═══════════════════════════════════════════════");
                return;
            }

            boolean adminUserExists = userRepository.findByEmail("admin@truckhire.com").isPresent();

            if (adminUserExists) {
                log.info("═══════════════════════════════════════════════");
                log.info("  ✓ Default admin user verified (admin@truckhire.com)");
                log.info("═══════════════════════════════════════════════");
            } else {
                log.warn("═══════════════════════════════════════════════");
                log.warn("  WARNING: No admin user found!");
                log.warn("  Expected: admin@truckhire.com");
                log.warn("  Check Flyway migration V3__seed_default_admin.sql");
                log.warn("═══════════════════════════════════════════════");
            }
        };
    }
}
