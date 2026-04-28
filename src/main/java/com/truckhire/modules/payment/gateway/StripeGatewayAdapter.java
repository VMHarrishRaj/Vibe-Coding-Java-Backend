package com.truckhire.modules.payment.gateway;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.AccountLink;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferCreateParams;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.payment.config.PaymentConfig;
import com.truckhire.modules.user.entity.User;
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

    /**
     * Create a Stripe Express Connected Account for an owner.
     * Express accounts give owners a Stripe-hosted dashboard and handle KYC on Stripe's side.
     *
     * KYC pre-fill: any profile fields already stored on the User are passed to Stripe
     * so the onboarding form comes up pre-populated. In test mode this means the tester
     * only has to enter the bank details — all personal info is already filled in.
     * In production Stripe will use the same pre-filled data for real KYC.
     *
     * All individual fields are optional — Stripe ignores nulls gracefully.
     *
     * Returns the new Stripe account ID (acct_...).
     */
    public String createConnectedAccount(User owner, String platformUrl) {
        try {
            // Build the individual pre-fill block with whatever profile data we have.
            //
            // FIELDS INCLUDED:
            //   - email, first/last name, DOB: safe — no format constraints beyond basic validity
            //   - line1, city, zipcode: safe — free-form strings Stripe accepts as-is
            //   - business_profile.url: pre-filled with platformUrl (APP_BASE_URL) so the
            //     "Provide a business website" requirement does not block onboarding.
            //     Stripe requires this field on all Express accounts (currently_due).
            //
            // FIELDS INTENTIONALLY EXCLUDED:
            //   - phone: DB stores raw digits (e.g. "9001234567"); Stripe requires E.164
            //     format ("+19001234567"). Conversion is fragile without knowing country code.
            //   - state: DB stores full names (e.g. "Texas"); Stripe requires ISO 3166-2
            //     subdivision codes (e.g. "TX"). Owner enters this on Stripe's form.
            //   - country (address + top-level): DB stores full names (e.g. "India");
            //     Stripe requires ISO alpha-2 (e.g. "US"). Also, address.country must
            //     match the top-level account country — safer to let Stripe's form handle both.
            AccountCreateParams.Individual.Builder individualBuilder =
                    AccountCreateParams.Individual.builder()
                            .setEmail(owner.getEmail());

            if (owner.getFullname() != null && !owner.getFullname().isBlank()) {
                String[] parts = owner.getFullname().trim().split("\\s+", 2);
                individualBuilder.setFirstName(parts[0]);
                if (parts.length > 1) {
                    individualBuilder.setLastName(parts[1]);
                }
            }

            if (owner.getDob() != null) {
                individualBuilder.setDob(
                        AccountCreateParams.Individual.Dob.builder()
                                .setDay((long) owner.getDob().getDayOfMonth())
                                .setMonth((long) owner.getDob().getMonthValue())
                                .setYear((long) owner.getDob().getYear())
                                .build()
                );
            }

            // Address pre-fill: only line1, city, zipcode — safe free-form fields.
            // State and country are excluded (see comment above).
            AccountCreateParams.Individual.Address.Builder addrBuilder =
                    AccountCreateParams.Individual.Address.builder();
            boolean hasAddress = false;
            if (owner.getAddress() != null && !owner.getAddress().isBlank()) {
                addrBuilder.setLine1(owner.getAddress());
                hasAddress = true;
            }
            if (owner.getCity() != null && !owner.getCity().isBlank()) {
                addrBuilder.setCity(owner.getCity());
                hasAddress = true;
            }
            // Zipcode is intentionally excluded because Stripe strictly validates it based on the
            // inferred country (e.g., US if not provided), which breaks testing with dummy data.
            // if (owner.getZipcode() != null && !owner.getZipcode().isBlank()) {
            //     addrBuilder.setPostalCode(owner.getZipcode());
            //     hasAddress = true;
            // }
            if (hasAddress) {
                individualBuilder.setAddress(addrBuilder.build());
            }

            AccountCreateParams params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setEmail(owner.getEmail())
                    .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                    .setIndividual(individualBuilder.build())
                    .setBusinessProfile(AccountCreateParams.BusinessProfile.builder()
                            .setUrl(platformUrl)
                            .build())
                    .setCapabilities(AccountCreateParams.Capabilities.builder()
                            .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                    .setRequested(true)
                                    .build())
                            .build())
                    .build();

            Account account = client().accounts().create(params);
            log.info("Stripe Connected Account created: accountId={}, email={}", account.getId(), owner.getEmail());
            return account.getId();

        } catch (StripeException e) {
            log.error("Stripe createConnectedAccount failed: {}", e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create Stripe Connected Account: " + e.getMessage());
        }
    }

    /**
     * Generate a Stripe Account Link (onboarding URL) for the given Connected Account.
     * The link expires after a short period — always generate fresh on each request.
     *
     * refreshUrl: where Stripe redirects if the link expires before completion.
     * returnUrl:  where Stripe redirects after the owner completes (or abandons) onboarding.
     */
    public String createAccountLink(String stripeAccountId, String returnUrl, String refreshUrl) {
        try {
            AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                    .setAccount(stripeAccountId)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setReturnUrl(returnUrl)
                    .setRefreshUrl(refreshUrl)
                    .build();

            AccountLink link = client().accountLinks().create(params);
            log.info("Stripe Account Link created: accountId={}, url={}", stripeAccountId, link.getUrl());
            return link.getUrl();

        } catch (StripeException e) {
            log.error("Stripe createAccountLink failed: accountId={}, error={}", stripeAccountId, e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create Stripe onboarding link: " + e.getMessage());
        }
    }

    /**
     * Retrieve a Connected Account to verify onboarding completion status.
     * chargesEnabled=true means the owner has completed Stripe's KYC and can receive transfers.
     */
    public boolean isAccountOnboardingComplete(String stripeAccountId) {
        try {
            Account account = client().accounts().retrieve(stripeAccountId);
            // Both chargesEnabled AND payoutsEnabled must be true.
            // chargesEnabled alone is insufficient — an account can accept payments but still
            // have payouts blocked (e.g. no bank account added yet). We need payoutsEnabled=true
            // to ensure the owner can actually receive money from the platform.
            return Boolean.TRUE.equals(account.getChargesEnabled())
                    && Boolean.TRUE.equals(account.getPayoutsEnabled());
        } catch (StripeException e) {
            log.error("Stripe account retrieve failed: accountId={}, error={}", stripeAccountId, e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to verify Stripe account status: " + e.getMessage());
        }
    }

    /**
     * Retrieve a Stripe Charge by ID to extract card details (brand, last4).
     * Returns null on any error — callers must treat this as best-effort.
     */
    public Charge retrieveCharge(String chargeId) {
        try {
            return client().charges().retrieve(chargeId);
        } catch (StripeException e) {
            log.warn("Stripe retrieveCharge failed: chargeId={}, error={}", chargeId, e.getMessage());
            return null;
        }
    }

    private long toGatewayAmount(BigDecimal amount, String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW" -> amount.longValue();
            default -> amount.multiply(BigDecimal.valueOf(100)).longValue();
        };
    }
}
