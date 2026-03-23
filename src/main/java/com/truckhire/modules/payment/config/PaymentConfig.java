package com.truckhire.modules.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Payment gateway credentials loaded from application.yml + .env.
 *
 * WHY @ConfigurationProperties over @Value?
 * Groups related config under one class. Easier to test, mock, and pass around.
 * The actual values come from environment variables via the yml placeholders:
 *   app.payment.razorpay.key-id = ${RAZORPAY_KEY_ID:}
 *
 * Active gateway and currency are intentionally NOT here — they live in the DB
 * (platform_settings) so admin can change them without a redeploy.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentConfig {

    private Razorpay razorpay = new Razorpay();
    private Stripe stripe = new Stripe();

    @Getter
    @Setter
    public static class Razorpay {
        private String keyId;
        private String keySecret;
        private String webhookSecret;
        private String accountNumber;  // Razorpay X virtual account number (required for Payout API)
    }

    @Getter
    @Setter
    public static class Stripe {
        private String secretKey;
        private String publishableKey;
        private String webhookSecret;
    }
}
