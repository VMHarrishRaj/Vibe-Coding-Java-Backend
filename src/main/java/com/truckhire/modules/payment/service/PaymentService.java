package com.truckhire.modules.payment.service;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.truckhire.common.exception.BusinessException;
import com.truckhire.common.exception.ResourceNotFoundException;
import com.truckhire.modules.booking.entity.Booking;
import com.truckhire.modules.booking.entity.BookingStatus;
import com.truckhire.modules.booking.repository.BookingRepository;
import com.truckhire.modules.payment.config.PaymentConfig;
import com.truckhire.modules.payment.dto.PaymentInitiatedResponse;
import com.truckhire.modules.payment.dto.PaymentStatusResponse;
import com.truckhire.modules.payment.entity.PaymentTransaction;
import com.truckhire.modules.payment.entity.PlatformSettings;
import com.truckhire.modules.payment.enums.PaymentGateway;
import com.truckhire.modules.payment.enums.PaymentStatus;
import com.truckhire.modules.payment.enums.PaymentType;
import com.truckhire.modules.payment.dto.LinkBankAccountRequest;
import com.truckhire.modules.payment.gateway.GatewayPort;
import com.truckhire.modules.payment.gateway.RazorpayGatewayAdapter;
import com.truckhire.modules.payment.gateway.StripeGatewayAdapter;
import com.truckhire.modules.payment.repository.PaymentTransactionRepository;
import com.truckhire.modules.user.entity.User;
import com.truckhire.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PaymentService — orchestrates the full payment lifecycle.
 *
 * Responsibilities:
 * 1. Initiate payment (create gateway order, save PENDING transaction)
 * 2. Verify Razorpay HMAC signature → mark SUCCEEDED + confirm booking
 * 3. Handle webhook events (idempotent — skip if already processed)
 * 4. Refund when booking is cancelled after payment
 * 5. Trigger owner payout when booking is COMPLETED
 *
 * Gateway selection:
 * PaymentService reads platform_settings.active_gateway and delegates to
 * RazorpayGatewayAdapter or StripeGatewayAdapter accordingly.
 * This is the "use the right adapter at runtime" pattern — no code change
 * required to switch gateways.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PlatformSettingsService platformSettingsService;
    private final PaymentTransactionRepository transactionRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PaymentConfig paymentConfig;
    private final RazorpayGatewayAdapter razorpayAdapter;
    private final StripeGatewayAdapter stripeAdapter;

    // ═══════════════════════════════════════
    // INITIATE PAYMENT
    // ═══════════════════════════════════════

    /**
     * Step 1 of payment flow: create a gateway order and return the SDK payload to frontend.
     *
     * Guards:
     * - Booking must be PENDING (not yet confirmed — payment triggers confirmation)
     * - Renter must be the one initiating (passed in as currentUserId)
     * - No existing SUCCEEDED CHARGE for this booking (idempotency)
     */
    @Transactional
    public PaymentInitiatedResponse initiatePayment(UUID bookingId, UUID renterId) {
        Booking booking = loadBooking(bookingId);

        if (!booking.getRenter().getId().equals(renterId)) {
            throw new BusinessException("NOT_BOOKING_RENTER", "You are not the renter for this booking");
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException("INVALID_BOOKING_STATUS",
                    "Payment can only be initiated for PENDING bookings. Current status: " + booking.getStatus());
        }

        // Idempotency: if a PENDING transaction already exists for this booking, reuse it
        Optional<PaymentTransaction> existingPending = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.PENDING);
        if (existingPending.isPresent()) {
            return buildInitiatedResponse(existingPending.get(), booking);
        }

        PlatformSettings settings = platformSettingsService.getSettings();
        PaymentGateway gateway = settings.getActiveGateway();
        String currency = settings.getActiveCurrency();

        // Use dayAmount at booking time — totalAmount is not yet known (mileage computed on return)
        BigDecimal amount = booking.getDayAmount();
        GatewayPort adapter = selectAdapter(gateway);
        String gatewayOrderId = adapter.createOrder(amount, currency, booking.getBookingNumber());

        PaymentTransaction txn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(gateway)
                .gatewayOrderId(gatewayOrderId)
                .amount(amount)
                .currency(currency)
                .status(PaymentStatus.PENDING)
                .type(PaymentType.CHARGE)
                .build();
        transactionRepository.save(txn);

        log.info("Payment initiated: bookingId={}, gateway={}, orderId={}", bookingId, gateway, gatewayOrderId);
        return buildInitiatedResponse(txn, booking);
    }

    // ═══════════════════════════════════════
    // VERIFY PAYMENT (RAZORPAY ONLY)
    // ═══════════════════════════════════════

    /**
     * Step 3 (Razorpay flow): verify HMAC signature, mark SUCCEEDED, confirm booking.
     *
     * Why HMAC verification server-side?
     * Razorpay signs the payment result with HMAC-SHA256 using your key_secret.
     * Verifying this on the server ensures the payment result wasn't tampered with.
     * Never trust a payment result that isn't signature-verified.
     */
    @Transactional
    public void verifyRazorpayPayment(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        PaymentTransaction txn = transactionRepository.findByGatewayOrderId(razorpayOrderId)
                .orElseThrow(() -> new BusinessException("PAYMENT_NOT_FOUND",
                        "No payment transaction found for order: " + razorpayOrderId));

        if (txn.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment already verified (idempotent): orderId={}", razorpayOrderId);
            return;
        }

        // Verify signature using Razorpay SDK Utils.verifyPaymentSignature()
        // It computes HMAC-SHA256(keySecret, "<orderId>|<paymentId>") internally
        try {
            org.json.JSONObject attributes = new org.json.JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId);
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_signature", razorpaySignature);
            boolean valid = Utils.verifyPaymentSignature(attributes, paymentConfig.getRazorpay().getKeySecret());
            if (!valid) {
                txn.setStatus(PaymentStatus.FAILED);
                txn.setFailureReason("Signature verification failed");
                transactionRepository.save(txn);
                throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                        "Payment signature verification failed. Payment not authorised.");
            }
        } catch (RazorpayException e) {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setFailureReason("Signature verification error: " + e.getMessage());
            transactionRepository.save(txn);
            throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                    "Payment signature verification failed. Payment not authorised.");
        }

        txn.setStatus(PaymentStatus.SUCCEEDED);
        txn.setGatewayPaymentId(razorpayPaymentId);
        txn.setGatewaySignature(razorpaySignature);
        transactionRepository.save(txn);

        confirmBooking(txn.getBooking().getId());
        log.info("Razorpay payment verified: orderId={}, paymentId={}", razorpayOrderId, razorpayPaymentId);
    }

    // ═══════════════════════════════════════
    // WEBHOOK HANDLERS (IDEMPOTENT)
    // ═══════════════════════════════════════

    /**
     * Handles Razorpay webhook event: payment.captured
     * Idempotent — skips if transaction already SUCCEEDED (verify endpoint may have already run).
     */
    @Transactional
    public void handleRazorpayWebhook(String orderId, String paymentId) {
        transactionRepository.findByGatewayOrderId(orderId).ifPresent(txn -> {
            if (txn.getStatus() == PaymentStatus.SUCCEEDED) {
                log.debug("Razorpay webhook: already SUCCEEDED, skipping. orderId={}", orderId);
                return;
            }
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setGatewayPaymentId(paymentId);
            transactionRepository.save(txn);
            confirmBooking(txn.getBooking().getId());
            log.info("Razorpay webhook: booking confirmed via webhook. orderId={}", orderId);
        });
    }

    /**
     * Handles Stripe webhook event: payment_intent.succeeded
     */
    @Transactional
    public void handleStripeWebhook(String paymentIntentId) {
        transactionRepository.findByGatewayOrderId(paymentIntentId).ifPresent(txn -> {
            if (txn.getStatus() == PaymentStatus.SUCCEEDED) {
                log.debug("Stripe webhook: already SUCCEEDED, skipping. piId={}", paymentIntentId);
                return;
            }
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setGatewayPaymentId(paymentIntentId);
            transactionRepository.save(txn);
            confirmBooking(txn.getBooking().getId());
            log.info("Stripe webhook: booking confirmed via webhook. piId={}", paymentIntentId);
        });
    }

    // ═══════════════════════════════════════
    // REFUND (CALLED BY BOOKING CANCELLATION)
    // ═══════════════════════════════════════

    /**
     * Triggered when a CONFIRMED booking is cancelled (renter, owner, or admin).
     * If payment was already SUCCEEDED, issues a refund via the gateway.
     * If no payment or not yet SUCCEEDED, nothing to refund — returns silently.
     *
     * This is a "fire and update" pattern:
     * - If gateway call succeeds → save REFUND row, mark original REFUNDED
     * - If gateway call fails → log the error, throw so cancellation can decide what to do
     */
    @Transactional
    public void refundIfPaid(UUID bookingId) {
        Optional<PaymentTransaction> chargeOpt = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.SUCCEEDED);

        if (chargeOpt.isEmpty()) {
            log.debug("No succeeded CHARGE found for bookingId={} — no refund needed", bookingId);
            return;
        }

        PaymentTransaction charge = chargeOpt.get();
        GatewayPort adapter = selectAdapter(charge.getGateway());

        String refundId = adapter.refund(charge.getGatewayPaymentId(), charge.getAmount(), charge.getCurrency());

        // Save the refund transaction
        PaymentTransaction refundTxn = PaymentTransaction.builder()
                .booking(charge.getBooking())
                .gateway(charge.getGateway())
                .gatewayTransferId(refundId)
                .amount(charge.getAmount())
                .currency(charge.getCurrency())
                .status(PaymentStatus.SUCCEEDED)
                .type(PaymentType.REFUND)
                .build();
        transactionRepository.save(refundTxn);

        // Mark original charge as REFUNDED
        charge.setStatus(PaymentStatus.REFUNDED);
        transactionRepository.save(charge);

        log.info("Refund completed: bookingId={}, refundId={}", bookingId, refundId);
    }

    // ═══════════════════════════════════════
    // OWNER PAYOUT (CALLED ON BOOKING COMPLETED)
    // ═══════════════════════════════════════

    /**
     * Triggered when a booking reaches COMPLETED (owner records odometer end).
     *
     * Calculates:
     * - platformFee = totalAmount * (platformFeePercent / 100)
     * - ownerAmount = totalAmount - platformFee
     *
     * Then initiates a transfer to the owner's linked gateway account.
     * If the owner has no gateway account linked, logs a warning and skips
     * (booking still completes — payout can be retried manually).
     */
    @Transactional
    public void initiateOwnerPayout(UUID bookingId) {
        // Guard: reject if a payout has already been initiated or completed
        boolean payoutAlreadyExists = transactionRepository.existsByBookingIdAndTypeAndStatusIn(
                bookingId, PaymentType.PAYOUT,
                java.util.List.of(PaymentStatus.PAYOUT_PENDING, PaymentStatus.PAID_OUT));
        if (payoutAlreadyExists) {
            throw new BusinessException("PAYOUT_ALREADY_EXISTS",
                    "A payout has already been initiated or completed for this booking.");
        }

        Optional<PaymentTransaction> chargeOpt = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.SUCCEEDED);

        if (chargeOpt.isEmpty()) {
            throw new BusinessException("PAYMENT_NOT_FOUND",
                    "No completed payment found for this booking. Cannot initiate payout.");
        }

        PaymentTransaction charge = chargeOpt.get();
        Booking booking = charge.getBooking();
        User owner = booking.getOwner();

        // Use totalAmount if available (set on COMPLETED), otherwise dayAmount
        BigDecimal totalAmount = booking.getTotalAmount() != null
                ? booking.getTotalAmount()
                : charge.getAmount();

        PlatformSettings settings = platformSettingsService.getSettings();
        BigDecimal feePercent = settings.getPlatformFeePercent();
        BigDecimal platformFee = totalAmount
                .multiply(feePercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal ownerAmount = totalAmount.subtract(platformFee);

        // Update the original charge with fee breakdown
        charge.setPlatformFee(platformFee);
        charge.setOwnerAmount(ownerAmount);
        transactionRepository.save(charge);

        // Determine owner's gateway account ID
        String ownerGatewayId = resolveOwnerGatewayId(owner, charge.getGateway());
        if (ownerGatewayId == null) {
            log.warn("Owner {} has no {} account linked — payout skipped. Booking {} is still COMPLETED.",
                    owner.getId(), charge.getGateway(), bookingId);
            return;
        }

        GatewayPort adapter = selectAdapter(charge.getGateway());
        PaymentStatus payoutStatus;
        String transferId;

        try {
            transferId = adapter.initiatePayout(ownerGatewayId, ownerAmount, charge.getCurrency(),
                    booking.getBookingNumber());
            payoutStatus = PaymentStatus.PAID_OUT;
            log.info("Payout completed: bookingId={}, transferId={}, ownerAmount={}", bookingId, transferId, ownerAmount);
        } catch (BusinessException e) {
            // Payout failed — record PAYOUT_PENDING so admin can retry
            log.error("Payout failed for bookingId={}: {}", bookingId, e.getMessage());
            transferId = null;
            payoutStatus = PaymentStatus.PAYOUT_PENDING;
        }

        PaymentTransaction payoutTxn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(charge.getGateway())
                .gatewayTransferId(transferId)
                .amount(ownerAmount)
                .platformFee(platformFee)
                .ownerAmount(ownerAmount)
                .currency(charge.getCurrency())
                .status(payoutStatus)
                .type(PaymentType.PAYOUT)
                .build();
        transactionRepository.save(payoutTxn);
    }

    // ═══════════════════════════════════════
    // MILEAGE TOP-UP (SECOND CHARGE ON RETURN)
    // ═══════════════════════════════════════

    /**
     * Called from BookingService.recordOdometerEnd() when mileageAmount > 0.
     *
     * Why a second order?
     * Razorpay does not allow charging a card post-hoc without a new order.
     * The renter must complete a fresh Razorpay Checkout for the mileage delta.
     * This creates that order and saves a MILEAGE_TOPUP/PENDING transaction.
     * Frontend detects this via GET /bookings/{id}/payment and opens checkout.
     */
    @Transactional
    public void initiateMileageTopUp(UUID bookingId) {
        Booking booking = loadBooking(bookingId);

        // Idempotency: don't create a second order if one already exists
        Optional<PaymentTransaction> existing = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.MILEAGE_TOPUP, PaymentStatus.PENDING);
        if (existing.isPresent()) {
            log.debug("MILEAGE_TOPUP order already exists for bookingId={}", bookingId);
            return;
        }

        // Use the same gateway as the original CHARGE — not the currently active gateway.
        // If admin switched gateways between booking creation and rental return, we must keep
        // both charges on the same gateway so refunds work correctly.
        Optional<PaymentTransaction> chargeOpt = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.SUCCEEDED);
        PaymentGateway gateway;
        String currency;
        if (chargeOpt.isPresent()) {
            gateway = chargeOpt.get().getGateway();
            currency = chargeOpt.get().getCurrency();
        } else {
            // Fallback: no succeeded charge yet — use active gateway (edge case)
            PlatformSettings settings = platformSettingsService.getSettings();
            gateway = settings.getActiveGateway();
            currency = settings.getActiveCurrency();
        }

        BigDecimal mileageAmount = booking.getMileageAmount();
        GatewayPort adapter = selectAdapter(gateway);
        String gatewayOrderId = adapter.createOrder(mileageAmount, currency,
                booking.getBookingNumber() + "-MILES");

        PaymentTransaction txn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(gateway)
                .gatewayOrderId(gatewayOrderId)
                .amount(mileageAmount)
                .currency(currency)
                .status(PaymentStatus.PENDING)
                .type(PaymentType.MILEAGE_TOPUP)
                .build();
        transactionRepository.save(txn);

        log.info("Mileage top-up initiated: bookingId={}, orderId={}, amount={}",
                bookingId, gatewayOrderId, mileageAmount);
    }

    /**
     * Called by frontend after renter completes the mileage checkout.
     * Verifies HMAC signature (same logic as verifyRazorpayPayment), marks SUCCEEDED,
     * then fires owner payout since all charges are now collected.
     */
    @Transactional
    public void verifyMileagePayment(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
        PaymentTransaction txn = transactionRepository.findByGatewayOrderId(razorpayOrderId)
                .orElseThrow(() -> new BusinessException("PAYMENT_NOT_FOUND",
                        "No payment transaction found for order: " + razorpayOrderId));

        if (txn.getType() != PaymentType.MILEAGE_TOPUP) {
            throw new BusinessException("INVALID_PAYMENT_TYPE",
                    "This order is not a mileage top-up");
        }

        if (txn.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Mileage payment already verified (idempotent): orderId={}", razorpayOrderId);
            return;
        }

        try {
            org.json.JSONObject attributes = new org.json.JSONObject();
            attributes.put("razorpay_order_id", razorpayOrderId);
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_signature", razorpaySignature);
            boolean valid = com.razorpay.Utils.verifyPaymentSignature(attributes,
                    paymentConfig.getRazorpay().getKeySecret());
            if (!valid) {
                txn.setStatus(PaymentStatus.FAILED);
                txn.setFailureReason("Signature verification failed");
                transactionRepository.save(txn);
                throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                        "Mileage payment signature verification failed.");
            }
        } catch (RazorpayException e) {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setFailureReason("Signature verification error: " + e.getMessage());
            transactionRepository.save(txn);
            throw new BusinessException("PAYMENT_SIGNATURE_INVALID",
                    "Mileage payment signature verification failed.");
        }

        txn.setStatus(PaymentStatus.SUCCEEDED);
        txn.setGatewayPaymentId(razorpayPaymentId);
        txn.setGatewaySignature(razorpaySignature);
        transactionRepository.save(txn);

        // Payout is admin-initiated — no automatic payout here
        log.info("Mileage payment verified: orderId={}", razorpayOrderId);
    }

    // ═══════════════════════════════════════
    // OWNER BANK ACCOUNT LINKING
    // ═══════════════════════════════════════

    /**
     * Links an owner's bank account to Razorpay so payouts can be made.
     *
     * Razorpay requires two objects:
     * 1. Contact — represents the owner in Razorpay's system
     * 2. Fund Account — the bank account linked to that contact
     *
     * If a contact already exists (razorpayContactId on user), it is reused.
     * A new Fund Account is always created (owner may update bank details).
     */
    @Transactional
    public void linkOwnerBankAccount(UUID ownerId, LinkBankAccountRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ownerId));

        // Step 1: create contact if not already linked
        String contactId = owner.getRazorpayContactId();
        if (contactId == null || contactId.isBlank()) {
            contactId = razorpayAdapter.createContact(
                    owner.getFullname(), owner.getEmail(), owner.getPhone());
            owner.setRazorpayContactId(contactId);
        }

        // Step 2: create fund account linked to that contact
        String fundAccountId = razorpayAdapter.createFundAccount(
                contactId,
                request.getAccountHolderName(),
                request.getAccountNumber(),
                request.getIfscCode());

        owner.setRazorpayFundAccountId(fundAccountId);
        userRepository.save(owner);

        log.info("Owner bank account linked: ownerId={}, contactId={}, fundAccountId={}",
                ownerId, contactId, fundAccountId);
    }

    // ═══════════════════════════════════════
    // PAYMENT STATUS QUERY
    // ═══════════════════════════════════════

    @Transactional(readOnly = true)
    public PaymentStatusResponse getPaymentStatus(UUID bookingId) {
        // Return the most recent transaction for this booking (any type).
        // After return, this will surface MILEAGE_TOPUP/PENDING so frontend knows to open checkout again.
        // If no transaction exists yet (renter hasn't initiated payment), return NOT_INITIATED instead of 404.
        Optional<PaymentTransaction> txnOpt = transactionRepository
                .findFirstByBookingIdOrderByCreatedAtDesc(bookingId);

        if (txnOpt.isEmpty()) {
            return PaymentStatusResponse.builder()
                    .bookingId(bookingId.toString())
                    .status(PaymentStatus.NOT_INITIATED.name())
                    .build();
        }

        PaymentTransaction txn = txnOpt.get();
        return PaymentStatusResponse.builder()
                .bookingId(bookingId.toString())
                .status(txn.getStatus().name())
                .gateway(txn.getGateway().name())
                .type(txn.getType().name())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .gatewayOrderId(txn.getGatewayOrderId())
                .gatewayPaymentId(txn.getGatewayPaymentId())
                .createdAt(txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null)
                .updatedAt(txn.getUpdatedAt() != null ? txn.getUpdatedAt().toString() : null)
                .build();
    }

    // ═══════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════

    private GatewayPort selectAdapter(PaymentGateway gateway) {
        return gateway == PaymentGateway.RAZORPAY ? razorpayAdapter : stripeAdapter;
    }

    private Booking loadBooking(UUID bookingId) {
        return bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));
    }

    private void confirmBooking(UUID bookingId) {
        bookingRepository.findById(bookingId).ifPresent(booking -> {
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.setStatus(BookingStatus.AWAITING_APPROVAL);
                bookingRepository.save(booking);
                log.info("Payment captured — booking awaiting owner approval: bookingId={}", bookingId);
            }
        });
    }

    private String resolveOwnerGatewayId(User owner, PaymentGateway gateway) {
        return gateway == PaymentGateway.RAZORPAY
                ? owner.getRazorpayFundAccountId()
                : owner.getStripeAccountId();
    }

    private PaymentInitiatedResponse buildInitiatedResponse(PaymentTransaction txn, Booking booking) {
        PlatformSettings settings = platformSettingsService.getSettings();
        long amountInSmallestUnit = toGatewayAmount(txn.getAmount(), txn.getCurrency());

        if (txn.getGateway() == PaymentGateway.RAZORPAY) {
            return PaymentInitiatedResponse.builder()
                    .gateway("RAZORPAY")
                    .orderId(txn.getGatewayOrderId())
                    .keyId(paymentConfig.getRazorpay().getKeyId())
                    .bookingId(booking.getId().toString())
                    .amount(amountInSmallestUnit)
                    .currency(txn.getCurrency())
                    .build();
        } else {
            String clientSecret = stripeAdapter.getClientSecret(txn.getGatewayOrderId());
            return PaymentInitiatedResponse.builder()
                    .gateway("STRIPE")
                    .clientSecret(clientSecret)
                    .publishableKey(paymentConfig.getStripe().getPublishableKey())
                    .bookingId(booking.getId().toString())
                    .amount(amountInSmallestUnit)
                    .currency(txn.getCurrency())
                    .build();
        }
    }

    private long toGatewayAmount(BigDecimal amount, String currency) {
        return switch (currency.toUpperCase()) {
            case "JPY", "KRW" -> amount.longValue();
            default -> amount.multiply(BigDecimal.valueOf(100)).longValue();
        };
    }

}
