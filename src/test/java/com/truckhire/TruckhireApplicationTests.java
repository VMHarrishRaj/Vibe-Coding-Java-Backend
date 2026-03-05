package com.truckhire;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies the Spring application context loads successfully.
 *
 * This test will FAIL if:
 * - Any bean has a missing dependency
 * - Any configuration is invalid
 * - The database connection fails
 *
 * NOTE: This requires PostgreSQL to be running.
 * For CI without a DB, use @SpringBootTest with a test profile
 * that uses an in-memory DB like H2.
 */
@SpringBootTest
@ActiveProfiles("dev")
class TruckhireApplicationTests {

    @Test
    void contextLoads() {
        // If this test passes, the application context loaded successfully.
        // All beans were created, all configs were valid.
    }
}
