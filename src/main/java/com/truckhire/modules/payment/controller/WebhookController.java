package com.truckhire.modules.payment.controller;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import com.truckhire.modules.payment.config.PaymentConfig;
import com.truckhire.modules.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * WebhookController — handles incoming webhook events from Razorpay and Stripe.
 *
 * These endpoints are PUBLIC (no JWT) — the gateway calls them directly.
 * Security is provided by webhook signature verification instead of JWT.
 * Both endpoints are IDEMPOTENT — safe to receive the same event multiple times.
 */
@Slf4j
@RestController
@RequestMapping("/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;
    private final PaymentConfig paymentConfig;

    /**
     * Razorpay webhook handler.
     * Verifies X-Razorpay-Signature using HMAC-SHA256 with webhook_secret.
     */
    @PostMapping("/razorpay")
    public ResponseEntity<String> razorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (signature == null) {
            log.warn("Razorpay webhook received without signature — rejected");
            return ResponseEntity.badRequest().body("Missing signature");
        }

        try {
            boolean valid = Utils.verifyWebhookSignature(
                    payload, signature, paymentConfig.getRazorpay().getWebhookSecret());
            if (!valid) {
                log.warn("Razorpay webhook signature mismatch — rejected");
                return ResponseEntity.status(401).body("Invalid signature");
            }
        } catch (RazorpayException e) {
            log.warn("Razorpay webhook signature verification error: {}", e.getMessage());
            return ResponseEntity.status(401).body("Invalid signature");
        }

        try {
            JSONObject event = new JSONObject(payload);
            String eventName = event.getString("event");
            log.info("Razorpay webhook received: event={}", eventName);

            if ("payment.captured".equals(eventName)) {
                JSONObject paymentEntity = event
                        .getJSONObject("payload")
                        .getJSONObject("payment")
                        .getJSONObject("entity");
                String orderId = paymentEntity.getString("order_id");
                String paymentId = paymentEntity.getString("id");
                paymentService.handleRazorpayWebhook(orderId, paymentId);
            }
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    /**
     * Stripe webhook handler.
     *
     * Uses the three-tier deserialization pattern recommended by Stripe:
     *
     * Tier 1 — getObject().isPresent(): safe path, API versions match exactly.
     * Tier 2 — deserializeUnsafe(): API versions differ but fields are compatible.
     *           This is the most common case when Stripe CLI uses a newer API version
     *           than what is pinned in the stripe-java SDK.
     * Tier 3 — raw JSON fallback: extract only the fields we need (just "id") directly
     *           from the raw event JSON when all else fails.
     *
     * WHY THIS MATTERS:
     * getDataObjectDeserializer().getObject() returns Optional.empty() when the
     * webhook API version (from Stripe CLI or Dashboard) doesn't exactly match the
     * version pinned in stripe-java SDK. Without the fallback tiers, the handler
     * silently does nothing and returns 200 — the most confusing failure mode.
     */
    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        if (signature == null) {
            log.warn("Stripe webhook received without signature — rejected");
            return ResponseEntity.badRequest().body("Missing signature");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, paymentConfig.getStripe().getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(401).body("Invalid signature");
        }

        log.info("Stripe webhook received: type={}, id={}", event.getType(), event.getId());

        if ("payment_intent.succeeded".equals(event.getType())) {
            String paymentIntentId = extractPaymentIntentId(event);
            if (paymentIntentId != null) {
                paymentService.handleStripeWebhook(paymentIntentId);
            } else {
                log.error("Stripe webhook: could not extract PaymentIntent ID from event {}", event.getId());
            }
        }

        // Always return 200 — returning 4xx/5xx causes Stripe to retry for up to 72 hours
        return ResponseEntity.ok("");
    }

    /**
     * Extracts the PaymentIntent ID from a Stripe event using the three-tier pattern.
     *
     * Tier 1: getObject() — exact API version match (ideal path)
     * Tier 2: deserializeUnsafe() — version mismatch but fields are compatible (common with Stripe CLI)
     * Tier 3: raw JSON — parse the id field directly from event data JSON (last resort)
     */
    private String extractPaymentIntentId(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();

        // Tier 1: versions match exactly
        if (deserializer.getObject().isPresent()) {
            StripeObject obj = deserializer.getObject().get();
            if (obj instanceof PaymentIntent pi) {
                log.debug("Stripe webhook: deserialized via Tier 1 (exact version match)");
                return pi.getId();
            }
        }

        // Tier 2: version mismatch — attempt best-effort deserialization
        try {
            StripeObject obj = deserializer.deserializeUnsafe();
            if (obj instanceof PaymentIntent pi) {
                log.debug("Stripe webhook: deserialized via Tier 2 (deserializeUnsafe)");
                return pi.getId();
            }
        } catch (EventDataObjectDeserializationException e) {
            log.warn("Stripe webhook: Tier 2 deserialization failed, falling back to raw JSON. Reason: {}",
                    e.getMessage());

            // Tier 3: raw JSON — extract just the "id" field we need
            try {
                String rawJson = e.getRawJson();
                if (rawJson != null) {
                    String id = new JSONObject(rawJson).optString("id", null);
                    if (id != null && !id.isBlank()) {
                        log.debug("Stripe webhook: extracted ID via Tier 3 (raw JSON)");
                        return id;
                    }
                }
            } catch (Exception jsonEx) {
                log.error("Stripe webhook: Tier 3 raw JSON extraction failed: {}", jsonEx.getMessage());
            }
        }

        return null;
    }
}
