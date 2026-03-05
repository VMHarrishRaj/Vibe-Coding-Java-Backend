package com.truckhire.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Standardized API Response wrapper.
 *
 * EVERY controller endpoint returns this wrapper, ensuring the mobile app
 * always gets a consistent JSON structure:
 *
 * Success:
 * {
 *   "success": true,
 *   "message": "Truck created successfully",
 *   "data": { ... },
 *   "timestamp": "2026-03-03T14:00:00Z"
 * }
 *
 * Error:
 * {
 *   "success": false,
 *   "message": "Truck is already booked for the selected dates",
 *   "error": "BOOKING_CONFLICT",
 *   "timestamp": "2026-03-03T14:00:00Z"
 * }
 *
 * @param <T>  The type of data being returned (e.g., TruckDTO, BookingDTO)
 */
@Data                   // Lombok: generates getters, setters, toString, equals, hashCode
@Builder                // Lombok: enables ApiResponse.builder().success(true).data(dto).build()
@NoArgsConstructor      // Lombok: generates empty constructor (needed for JSON deserialization)
@AllArgsConstructor     // Lombok: generates constructor with all fields
@JsonInclude(JsonInclude.Include.NON_NULL)  // Jackson: skip null fields in JSON output
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private String error;

    @Builder.Default
    private Instant timestamp = Instant.now();

    // ── Factory methods for convenience ──

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(String error, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .message(message)
                .build();
    }
}
