package com.truckhire.modules.payment.gateway;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferCreateParams;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.payment.config.PaymentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * StripeGatewayAdapter — Stripe implementation of GatewayPort.
 *
 * Uses StripeClient (instance-based, recommended over static Stripe.apiKey).
 *
 * PaymentIntent flow for mobile (React Native):
 * 1. Backend creates PaymentIntent with automatic_payment_methods + allow_redirects=NEVER
 *    → allow_redirects=NEVER restricts to card/Apple Pay/Google Pay only (no Klarna, Affirm etc.)
 *    → This means return_url is NOT required — safe for native mobile apps
 * 2. Backend returns client_secret to the mobile SDK
 * 3. Mobile SDK calls stripe.confirmPayment(clientSecret) — backend is NOT involved in confirmation
 * 4. Stripe sends payment_intent.succeeded webhook → backend marks SUCCEEDED + confirms booking
 */
@Slf4j
@Component
public class StripeGatewayAdapter implements GatewayPort {

    private final PaymentConfig paymentConfig;

    public StripeGatewayAdapter(PaymentConfig paymentConfig) {
        this.paymentConfig = paymentConfig;
    }

    private StripeClient client() {
        return new StripeClient(paymentConfig.getStripe().getSecretKey());
    }

    @Override
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(toGatewayAmount(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    .putMetadata("receipt", receipt)
                    // allow_redirects=NEVER: restricts to non-redirect payment methods only
                    // (cards, Apple Pay, Google Pay). return_url is not required with this setting.
                    // Required for native mobile apps where browser redirects break the UX.
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .setAllowRedirects(
                                            PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                                    .build()
                    )
                    .build();

            PaymentIntent intent = client().paymentIntents().create(params);
            log.info("Stripe PaymentIntent created: id={}, amount={}, currency={}", intent.getId(), amount, currency);
            return intent.getId();

        } catch (StripeException e) {
            log.error("Stripe createOrder failed: {}", e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create payment intent: " + e.getMessage());
        }
    }

    /**
     * Returns the client_secret for a PaymentIntent.
     * Frontend passes this to stripe.confirmPayment() in the mobile SDK.
     */
    public String getClientSecret(String paymentIntentId) {
        try {
            PaymentIntent intent = client().paymentIntents().retrieve(paymentIntentId);
            return intent.getClientSecret();
        } catch (StripeException e) {
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to retrieve payment intent: " + e.getMessage());
        }
    }

    @Override
    public String refund(String gatewayPaymentId, BigDecimal amount, String currency) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(gatewayPaymentId)
                    .setAmount(toGatewayAmount(amount, currency))
                    .build();

            var refund = client().refunds().create(params);
            log.info("Stripe refund created: refundId={}, paymentIntentId={}", refund.getId(), gatewayPaymentId);
            return refund.getId();

        } catch (StripeException e) {
            log.error("Stripe refund failed: paymentIntentId={}, error={}", gatewayPaymentId, e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to process refund: " + e.getMessage());
        }
    }

    @Override
    public String initiatePayout(String stripeAccountId, BigDecimal amount, String currency, String reference) {
        try {
            TransferCreateParams params = TransferCreateParams.builder()
                    .setAmount(toGatewayAmount(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    .setDestination(stripeAccountId)
                    .putMetadata("reference", reference)
                    .build();

            var transfer = client().transfers().create(params);
            log.info("Stripe transfer created: transferId={}, destination={}, amount={}",
                    transfer.getId(), stripeAccountId, amount);
            return transfer.getId();

        } catch (StripeException e) {
            log.error("Stripe transfer failed: accountId={}, error={}", stripeAccountId, e.getMessage());
            throw new BusinessException("PAYOUT_GATEWAY_ERROR",
                    "Failed to initiate payout: " + e.getMessage());
        }
    }

    private long toGatewayAmount(BigDecimal amount, String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW" -> amount.longValue();
            default -> amount.multiply(BigDecimal.valueOf(100)).longValue();
        };
    }
}
