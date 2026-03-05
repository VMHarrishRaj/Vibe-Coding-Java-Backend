package com.truckhire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TruckHire Application Entry Point.
 *
 * @SpringBootApplication is a convenience annotation that combines:
 *   - @Configuration:       This class can define beans
 *   - @EnableAutoConfiguration: Spring Boot auto-configures based on dependencies
 *   - @ComponentScan:       Scans com.truckhire.* for @Component, @Service, @Repository, @Controller
 *
 * When you run this class, Spring Boot:
 *   1. Starts embedded Tomcat on port 8080
 *   2. Connects to PostgreSQL using application.yml config
 *   3. Runs Flyway migrations
 *   4. Registers all beans (controllers, services, repositories)
 *   5. Applies security filters
 */
@SpringBootApplication
public class TruckhireApplication {

    public static void main(String[] args) {
        SpringApplication.run(TruckhireApplication.class, args);
    }
}
