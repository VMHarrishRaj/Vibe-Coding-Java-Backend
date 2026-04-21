package com.truckhire.modules.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording an odometer reading.
 * Used by renter at pickup (PUT /bookings/{id}/start) and return (PUT /bookings/{id}/return).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OdometerUpdateRequest {

    @NotNull(message = "Odometer reading is required")
    @Min(value = 0, message = "Odometer reading cannot be negative")
    private Integer odometerReading;

    private String notes;
}
