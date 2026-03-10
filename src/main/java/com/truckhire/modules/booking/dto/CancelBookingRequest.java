package com.truckhire.modules.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for cancellation endpoints.
 * Reason is optional.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelBookingRequest {
    private String reason;
}
