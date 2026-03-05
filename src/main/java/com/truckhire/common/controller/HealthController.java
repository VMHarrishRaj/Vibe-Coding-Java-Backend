package com.truckhire.common.controller;

import com.truckhire.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health Check Controller — a simple endpoint to verify the app is running.
 *
 * This is the FIRST controller in the application.
 * Use it to verify your Spring Boot setup is working:
 * curl http://localhost:8080/api/v1/health
 *
 * Expected response:
 * {
 * "success": true,
 * "message": "TruckHire API is running",
 * "timestamp": "2026-03-03T..."
 * }
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("TruckHire API is running", "OK");
    }
}
