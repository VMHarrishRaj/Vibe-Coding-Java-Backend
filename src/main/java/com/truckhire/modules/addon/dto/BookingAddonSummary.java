package com.truckhire.modules.addon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Embedded in BookingResponse and returned by GET /bookings/{id}/addons.
 * For RSA addons, contactPhone is populated so the renter can call for help.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingAddonSummary {

    private String id;
    private String addonType;
    private String name;
    private BigDecimal rateSnapshot;
    private Integer quantity;
    private BigDecimal totalCost;

    /** Populated for RSA addons — lets renter call the provider post-booking */
    private String contactPhone;
    private String contactEmail;
    private String availability;
}
