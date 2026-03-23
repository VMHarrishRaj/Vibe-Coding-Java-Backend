package com.truckhire.modules.payment.gateway;

import java.math.BigDecimal;

/**
 * GatewayPort — the interface every payment gateway adapter must implement.
 *
 * WHY AN INTERFACE?
 * PaymentService depends on this interface, not on Razorpay or Stripe directly.
 * To switch gateways, PaymentService picks the right adapter at runtime based
 * on platform_settings.active_gateway — no code change required.
 *
 * This is the "Port" in Hexagonal Architecture (Ports and Adapters):
 * - PaymentService = the core domain
 * - RazorpayGatewayAdapter / StripeGatewayAdapter = the adapters (plug in/out)
 */
public interface GatewayPort {

    /**
     * Create a payment order/intent on the gateway.
     * Returns the gateway-assigned order ID (Razorpay order_id or Stripe pi_...).
     *
     * @param amount   amount in currency units (e.g. 500.00 for ₹500)
     * @param currency ISO currency code (INR, USD)
     * @param receipt  human-readable reference (booking number)
     * @return gateway order ID
     */
    String createOrder(BigDecimal amount, String currency, String receipt);

    /**
     * Refund a captured payment.
     *
     * @param gatewayPaymentId the payment ID returned by the gateway after capture
     * @param amount           amount to refund (full refund for now)
     * @param currency         ISO currency code
     * @return gateway refund ID
     */
    String refund(String gatewayPaymentId, BigDecimal amount, String currency);

    /**
     * Initiate a payout to the owner's linked account.
     * For Razorpay: uses fund_account_id + Payouts API.
     * For Stripe: uses Connect Transfer to the owner's stripe_account_id.
     *
     * @param ownerGatewayId razorpay_fund_account_id or stripe_account_id
     * @param amount         amount to transfer in currency units
     * @param currency       ISO currency code
     * @param reference      booking number for audit trail
     * @return gateway transfer/payout ID
     */
    String initiatePayout(String ownerGatewayId, BigDecimal amount, String currency, String reference);
}
