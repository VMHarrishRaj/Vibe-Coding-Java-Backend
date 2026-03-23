package com.truckhire.modules.payment.gateway;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.modules.payment.config.PaymentConfig;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RazorpayGatewayAdapter — Razorpay implementation of GatewayPort.
 *
 * Razorpay amount convention:
 * All amounts are in the smallest currency unit (paise for INR, cents for USD).
 * ₹500 → 50000 paise. The toGatewayAmount() helper handles this conversion.
 *
 * Razorpay payment flow:
 * 1. Backend creates an Order (this adapter) → returns order_id
 * 2. Frontend opens Razorpay Checkout with order_id
 * 3. User pays → Razorpay returns razorpay_payment_id + signature to frontend
 * 4. Frontend POSTs those to /payments/verify
 * 5. Backend verifies HMAC signature (in PaymentService) and marks SUCCEEDED
 */
@Slf4j
@Component
public class RazorpayGatewayAdapter implements GatewayPort {

    private final PaymentConfig paymentConfig;

    public RazorpayGatewayAdapter(PaymentConfig paymentConfig) {
        this.paymentConfig = paymentConfig;
    }

    @Override
    public String createOrder(BigDecimal amount, String currency, String receipt) {
        try {
            RazorpayClient client = buildClient();

            JSONObject options = new JSONObject();
            options.put("amount", toGatewayAmount(amount, currency));
            options.put("currency", currency.toUpperCase());
            options.put("receipt", receipt);
            options.put("payment_capture", 1);  // auto-capture on payment

            Order order = client.orders.create(options);
            String orderId = order.get("id");
            log.info("Razorpay order created: orderId={}, amount={}, currency={}", orderId, amount, currency);
            return orderId;

        } catch (RazorpayException e) {
            log.error("Razorpay createOrder failed: {}", e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create payment order: " + e.getMessage());
        }
    }

    @Override
    public String refund(String gatewayPaymentId, BigDecimal amount, String currency) {
        try {
            RazorpayClient client = buildClient();

            JSONObject options = new JSONObject();
            options.put("amount", toGatewayAmount(amount, currency));

            var refund = client.payments.refund(gatewayPaymentId, options);
            String refundId = refund.get("id");
            log.info("Razorpay refund initiated: refundId={}, paymentId={}", refundId, gatewayPaymentId);
            return refundId;

        } catch (RazorpayException e) {
            log.error("Razorpay refund failed: paymentId={}, error={}", gatewayPaymentId, e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to process refund: " + e.getMessage());
        }
    }

    @Override
    public String initiatePayout(String fundAccountId, BigDecimal amount, String currency, String reference) {
        // Razorpay Payout API — pays out from a Razorpay X virtual account to the owner's fund account.
        // fundAccountId = owner's fund_account_id (fa_...) created via createFundAccount().
        // Requires: Razorpay X account activated + RAZORPAY_ACCOUNT_NUMBER env var set.
        try {
            String accountNumber = paymentConfig.getRazorpay().getAccountNumber();
            if (accountNumber == null || accountNumber.isBlank()) {
                throw new BusinessException("PAYOUT_CONFIG_ERROR",
                        "RAZORPAY_ACCOUNT_NUMBER is not configured. Cannot initiate payout.");
            }

            JSONObject body = new JSONObject();
            body.put("account_number", accountNumber);
            body.put("fund_account_id", fundAccountId);
            body.put("amount", toGatewayAmount(amount, currency));
            body.put("currency", currency.toUpperCase());
            body.put("mode", "NEFT");
            body.put("purpose", "payout");
            body.put("narration", reference);

            JSONObject response = razorpayPost("https://api.razorpay.com/v1/payouts", body);
            String payoutId = response.getString("id");
            log.info("Razorpay payout initiated: payoutId={}, fundAccountId={}, amount={}", payoutId, fundAccountId, amount);
            return payoutId;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Razorpay payout failed: fundAccountId={}, error={}", fundAccountId, e.getMessage());
            throw new BusinessException("PAYOUT_GATEWAY_ERROR",
                    "Failed to initiate payout: " + e.getMessage());
        }
    }

    /**
     * Creates a Razorpay Contact for the owner.
     * Contact = the owner's identity in Razorpay's system (needed before creating a fund account).
     * Returns the Razorpay contact_id (e.g. "cont_ABC123").
     *
     * Called via REST (not SDK) because razorpay-java 1.4.5 has no ContactClient.
     */
    public String createContact(String name, String email, String phone) {
        try {
            JSONObject body = new JSONObject();
            body.put("name", name);
            body.put("email", email);
            body.put("contact", phone);
            body.put("type", "vendor");

            JSONObject response = razorpayPost("https://api.razorpay.com/v1/contacts", body);
            String contactId = response.getString("id");
            log.info("Razorpay contact created: contactId={}", contactId);
            return contactId;
        } catch (Exception e) {
            log.error("Razorpay createContact failed: {}", e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create Razorpay contact: " + e.getMessage());
        }
    }

    /**
     * Creates a Razorpay Fund Account linked to a contact.
     * Fund Account = the owner's bank account registered against their contact.
     * Returns the Razorpay fund_account_id (e.g. "fa_ABC123").
     *
     * In test mode, any syntactically valid IFSC (e.g. RAZR0000001) and account
     * number are accepted — no real bank validation occurs.
     */
    public String createFundAccount(String contactId, String accountHolderName,
                                    String accountNumber, String ifscCode) {
        try {
            JSONObject bankAccount = new JSONObject();
            bankAccount.put("name", accountHolderName);
            bankAccount.put("ifsc", ifscCode);
            bankAccount.put("account_number", accountNumber);

            JSONObject body = new JSONObject();
            body.put("contact_id", contactId);
            body.put("account_type", "bank_account");
            body.put("bank_account", bankAccount);

            JSONObject response = razorpayPost("https://api.razorpay.com/v1/fund_accounts", body);
            String fundAccountId = response.getString("id");
            log.info("Razorpay fund account created: fundAccountId={}, contactId={}", fundAccountId, contactId);
            return fundAccountId;
        } catch (Exception e) {
            log.error("Razorpay createFundAccount failed: {}", e.getMessage());
            throw new BusinessException("PAYMENT_GATEWAY_ERROR",
                    "Failed to create Razorpay fund account: " + e.getMessage());
        }
    }

    /**
     * Internal helper: POST JSON to Razorpay REST API with Basic Auth.
     * Basic Auth = Base64(keyId:keySecret) — Razorpay's authentication for REST calls
     * not covered by the Java SDK.
     */
    private JSONObject razorpayPost(String urlString, JSONObject body) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");

        String credentials = paymentConfig.getRazorpay().getKeyId()
                + ":" + paymentConfig.getRazorpay().getKeySecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);

        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        var stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException("Razorpay API error " + status + ": " + responseBody);
        }
        return new JSONObject(responseBody);
    }

    private RazorpayClient buildClient() throws RazorpayException {
        return new RazorpayClient(
                paymentConfig.getRazorpay().getKeyId(),
                paymentConfig.getRazorpay().getKeySecret()
        );
    }

    /**
     * Convert human-readable amount to gateway smallest unit.
     * INR/USD/EUR/GBP: multiply by 100 (paise / cents)
     * JPY/KRW: zero-decimal — no multiplication
     */
    private long toGatewayAmount(BigDecimal amount, String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW" -> amount.longValue();
            default -> amount.multiply(BigDecimal.valueOf(100)).longValue();
        };
    }
}
