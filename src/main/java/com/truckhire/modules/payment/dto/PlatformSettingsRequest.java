package com.truckhire.modules.payment.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for PUT /admin/platform/settings.
 */
@Getter
@Setter
@NoArgsConstructor
public class PlatformSettingsRequest {

    @NotBlank(message = "activeGateway is required")
    @Pattern(regexp = "RAZORPAY|STRIPE", message = "activeGateway must be RAZORPAY or STRIPE")
    private String activeGateway;

    @NotBlank(message = "activeCurrency is required")
    @Pattern(regexp = "INR|USD|EUR|GBP", message = "activeCurrency must be one of: INR, USD, EUR, GBP")
    private String activeCurrency;

    @NotNull(message = "platformFeePercent is required")
    @DecimalMin(value = "0.0", message = "platformFeePercent must be >= 0")
    @DecimalMax(value = "50.0", message = "platformFeePercent must be <= 50")
    private java.math.BigDecimal platformFeePercent;
}
