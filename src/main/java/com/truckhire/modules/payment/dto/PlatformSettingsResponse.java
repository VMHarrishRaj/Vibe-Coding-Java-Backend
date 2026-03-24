package com.truckhire.modules.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Response for GET /admin/platform/settings and GET /config/payment.
 */
@Getter
@Builder
public class PlatformSettingsResponse {

    private String activeGateway;
    private String activeCurrency;
    private BigDecimal platformFeePercent;

    // Public key for frontend SDK initialization
    // keyId for Razorpay, publishableKey for Stripe
    private String publicKey;

    private String updatedAt;
}
