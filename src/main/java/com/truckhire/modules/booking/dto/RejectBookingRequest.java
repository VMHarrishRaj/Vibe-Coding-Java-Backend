package com.truckhire.modules.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for owner rejecting a booking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectBookingRequest {
    private String reason;
}
