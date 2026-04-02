package com.truckhire.modules.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a booking — POST /bookings
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingRequest {

    @NotNull(message = "Truck ID is required")
    private UUID truckId;

    @NotBlank(message = "Start date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Start date must be in YYYY-MM-DD format")
    private String startDate;

    @NotBlank(message = "End date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "End date must be in YYYY-MM-DD format")
    private String endDate;

    private String pickupLocation;
    private String dropoffLocation;
    private String renterNotes;

    /** RSA provider IDs from the admin catalog — renter picks one or none */
    private List<UUID> rsaAddonIds;

    /** Equipment IDs owned by the truck's owner — renter selects desired items */
    private List<UUID> equipmentIds;
}
