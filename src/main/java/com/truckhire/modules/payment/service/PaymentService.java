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
import com.truckhire.modules.payment.dto.StripeConnectResponse;
import com.truckhire.modules.payment.gateway.GatewayPort;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.base-url}")
    private String baseUrl;

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

        long invoiceSeq = transactionRepository.nextInvoiceSequence();
        String invoiceNumber = String.format("INV%03d", invoiceSeq);

        PaymentTransaction txn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(gateway)
                .gatewayOrderId(gatewayOrderId)
                .amount(amount)
                .currency(currency)
                .status(PaymentStatus.PENDING)
                .type(PaymentType.CHARGE)
                .invoiceNumber(invoiceNumber)
                .build();
        transactionRepository.save(txn);

        log.info("Payment initiated: bookingId={}, gateway={}, orderId={}, invoiceNumber={}", bookingId, gateway, gatewayOrderId, invoiceNumber);
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

            // Only CHARGE triggers booking confirmation (PENDING -> AWAITING_APPROVAL).
            // MILEAGE_TOPUP is collected after booking is already COMPLETED — no status change needed.
            if (txn.getType() == PaymentType.CHARGE) {
                confirmBooking(txn.getBooking().getId());
                log.info("Stripe webhook: booking confirmed via webhook. piId={}", paymentIntentId);
            } else {
                log.info("Stripe webhook: mileage payment confirmed, booking unchanged. piId={}, type={}",
                        paymentIntentId, txn.getType());
            }
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
    // MANUAL REFUND (ADMIN-INITIATED)
    // ═══════════════════════════════════════

    /**
     * Admin manually issues a refund for a booking, independent of cancellation flow.
     *
     * Guards:
     * - Booking must exist
     * - A SUCCEEDED CHARGE must exist (otherwise nothing to refund)
     * - A REFUND transaction must not already exist (idempotency guard)
     */
    @Transactional
    public void adminRefundBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));

        Optional<PaymentTransaction> chargeOpt = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.SUCCEEDED);

        if (chargeOpt.isEmpty()) {
            throw new BusinessException("NO_PAYMENT_TO_REFUND",
                    "No succeeded payment found for this booking — nothing to refund");
        }

        boolean alreadyRefunded = transactionRepository
                .existsByBookingIdAndType(bookingId, PaymentType.REFUND);
        if (alreadyRefunded) {
            throw new BusinessException("ALREADY_REFUNDED",
                    "A refund has already been issued for this booking");
        }

        PaymentTransaction charge = chargeOpt.get();
        GatewayPort adapter = selectAdapter(charge.getGateway());
        String refundId = adapter.refund(charge.getGatewayPaymentId(), charge.getAmount(), charge.getCurrency());

        PaymentTransaction refundTxn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(charge.getGateway())
                .gatewayTransferId(refundId)
                .amount(charge.getAmount())
                .currency(charge.getCurrency())
                .status(PaymentStatus.SUCCEEDED)
                .type(PaymentType.REFUND)
                .build();
        transactionRepository.save(refundTxn);

        charge.setStatus(PaymentStatus.REFUNDED);
        transactionRepository.save(charge);

        log.info("Admin manual refund completed: bookingId={}, refundId={}", bookingId, refundId);
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
     * If the owner has no gateway account linked, records a PAYOUT_PENDING transaction
     * so the admin can see the pending payout and retry it via POST /admin/bookings/{id}/payout.
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
            log.warn("Owner {} has no {} account linked — recording PAYOUT_PENDING. Booking {} is still COMPLETED.",
                    owner.getId(), charge.getGateway(), bookingId);
            PaymentTransaction pendingTxn = PaymentTransaction.builder()
                    .booking(booking)
                    .gateway(charge.getGateway())
                    .amount(ownerAmount)
                    .platformFee(platformFee)
                    .ownerAmount(ownerAmount)
                    .currency(charge.getCurrency())
                    .status(PaymentStatus.PAYOUT_PENDING)
                    .type(PaymentType.PAYOUT)
                    .build();
            transactionRepository.save(pendingTxn);
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
                request.getRoutingNumber());

        owner.setRazorpayFundAccountId(fundAccountId);
        userRepository.save(owner);

        log.info("Owner bank account linked: ownerId={}, contactId={}, fundAccountId={}",
                ownerId, contactId, fundAccountId);
    }

    // ═══════════════════════════════════════
    // STRIPE CONNECT ONBOARDING
    // ═══════════════════════════════════════

    /**
     * Step 1 of Stripe Connect onboarding: create a Connected Account (if not exists)
     * and return a fresh Account Link (onboarding URL) for the owner to visit.
     *
     * IDEMPOTENCY: If the owner already has a stripeAccountId (started onboarding before),
     * we reuse the existing account and generate a fresh Account Link.
     * Stripe Account Links expire — they cannot be reused, but the account persists.
     *
     * The ownerId is encoded in the returnUrl as a query param so the return handler
     * knows which user to update when Stripe redirects back.
     */
    @Transactional
    public StripeConnectResponse initiateStripeConnect(UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ownerId));

        // Reuse existing Connected Account, or create a new one
        String stripeAccountId = owner.getStripeAccountId();
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            stripeAccountId = stripeAdapter.createConnectedAccount(owner, baseUrl);
            owner.setStripeAccountId(stripeAccountId);
            userRepository.save(owner);
            log.info("Stripe Connected Account created: ownerId={}, accountId={}", ownerId, stripeAccountId);
        } else {
            log.info("Reusing existing Stripe Connected Account: ownerId={}, accountId={}", ownerId, stripeAccountId);
        }

        // Build return and refresh URLs — ownerId in query param for the return handler
        String returnUrl  = baseUrl + "/api/v1/stripe/connect/return?ownerId=" + ownerId;
        String refreshUrl = baseUrl + "/api/v1/stripe/connect/refresh?ownerId=" + ownerId + "&accountId=" + stripeAccountId;

        String onboardingUrl = stripeAdapter.createAccountLink(stripeAccountId, returnUrl, refreshUrl);
        return StripeConnectResponse.builder().onboardingUrl(onboardingUrl).build();
    }

    /**
     * Step 2 of Stripe Connect onboarding: called when Stripe redirects the owner back
     * after completing (or abandoning) the onboarding flow.
     *
     * Verifies that the account has completed onboarding (chargesEnabled = true).
     * The stripeAccountId was already saved in step 1 — this just confirms completion.
     *
     * NOTE: Stripe does NOT pass the account ID in the return URL automatically —
     * we use the ownerId param to look up the user and check their stored stripeAccountId.
     */
    @Transactional
    public boolean completeStripeConnect(UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", ownerId));

        String stripeAccountId = owner.getStripeAccountId();
        if (stripeAccountId == null || stripeAccountId.isBlank()) {
            throw new BusinessException("STRIPE_CONNECT_ERROR",
                    "No Stripe account found for this owner. Please restart onboarding.");
        }

        boolean complete = stripeAdapter.isAccountOnboardingComplete(stripeAccountId);
        log.info("Stripe Connect return: ownerId={}, accountId={}, complete={}", ownerId, stripeAccountId, complete);
        return complete;
    }

    // ═══════════════════════════════════════
    // PAYMENT STATUS QUERY
    // ═══════════════════════════════════════

    @Transactional(readOnly = true)
    public PaymentStatusResponse getPaymentStatus(UUID bookingId) {
        // Priority: always surface MILEAGE_TOPUP/PENDING first if one exists.
        // Without this, a PAYOUT created after the mileage order (e.g. admin triggers payout early)
        // would overshadow the MILEAGE_TOPUP and the renter would never see the pay prompt.
        Optional<PaymentTransaction> pendingMileage = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.MILEAGE_TOPUP, PaymentStatus.PENDING);
        if (pendingMileage.isPresent()) {
            PaymentTransaction txn = pendingMileage.get();
            return buildPaymentStatusResponse(bookingId, txn);
        }

        // Otherwise return the most recent transaction (any type).
        // If no transaction exists yet, return NOT_INITIATED instead of 404.
        Optional<PaymentTransaction> txnOpt = transactionRepository
                .findFirstByBookingIdOrderByCreatedAtDesc(bookingId);

        if (txnOpt.isEmpty()) {
            return PaymentStatusResponse.builder()
                    .bookingId(bookingId.toString())
                    .status(PaymentStatus.NOT_INITIATED.name())
                    .build();
        }

        return buildPaymentStatusResponse(bookingId, txnOpt.get());
    }

    private PaymentStatusResponse buildPaymentStatusResponse(UUID bookingId, PaymentTransaction txn) {
        // For Stripe PENDING transactions, retrieve the clientSecret so mobile can open the payment sheet.
        // This covers both initial CHARGE and MILEAGE_TOPUP flows — same pattern, no extra endpoint needed.
        String clientSecret = null;
        if (txn.getGateway() == PaymentGateway.STRIPE
                && txn.getStatus() == PaymentStatus.PENDING
                && txn.getGatewayOrderId() != null) {
            clientSecret = stripeAdapter.getClientSecret(txn.getGatewayOrderId());
        }

        return PaymentStatusResponse.builder()
                .bookingId(bookingId.toString())
                .status(txn.getStatus().name())
                .gateway(txn.getGateway().name())
                .type(txn.getType().name())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .gatewayOrderId(txn.getGatewayOrderId())
                .gatewayPaymentId(txn.getGatewayPaymentId())
                .clientSecret(clientSecret)
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

    // ═══════════════════════════════════════
    // ADMIN PAYMENTS LIST + DETAIL
    // ═══════════════════════════════════════

    @Transactional(readOnly = true)
    public com.truckhire.common.dto.PagedResponse<com.truckhire.modules.payment.dto.AdminPaymentListResponse>
            getAdminPayments(String search, String settlementStatus, org.springframework.data.domain.Pageable pageable) {

        org.springframework.data.domain.Page<PaymentTransaction> page;
        boolean hasSearch = search != null && !search.isBlank();
        boolean hasStatus = settlementStatus != null && !settlementStatus.isBlank();

        PaymentStatus statusFilter = null;
        if (hasStatus) {
            try {
                statusFilter = PaymentStatus.valueOf(settlementStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Unrecognized status — treat as no filter
                hasStatus = false;
            }
        }

        if (hasSearch && hasStatus) {
            String q = "%" + search.toLowerCase().trim() + "%";
            page = transactionRepository.searchChargesByStatus(q, statusFilter, pageable);
        } else if (hasSearch) {
            String q = "%" + search.toLowerCase().trim() + "%";
            page = transactionRepository.searchChargesWithDetails(q, pageable);
        } else if (hasStatus) {
            page = transactionRepository.findChargesByStatus(statusFilter, pageable);
        } else {
            page = transactionRepository.findAllChargesWithDetails(pageable);
        }

        java.util.List<com.truckhire.modules.payment.dto.AdminPaymentListResponse> content =
                page.getContent().stream()
                        .map(this::mapToAdminPaymentListResponse)
                        .collect(java.util.stream.Collectors.toList());

        return com.truckhire.common.dto.PagedResponse.<com.truckhire.modules.payment.dto.AdminPaymentListResponse>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse getAdminInvoiceDetail(UUID transactionId) {
        PaymentTransaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new com.truckhire.common.exception.ResourceNotFoundException(
                        "PaymentTransaction", "id", transactionId));

        if (txn.getType() != PaymentType.CHARGE && txn.getType() != PaymentType.MILEAGE_TOPUP) {
            throw new com.truckhire.common.exception.BusinessException(
                    "INVALID_TRANSACTION_TYPE", "Invoice detail is only available for CHARGE or MILEAGE_TOPUP transactions.");
        }

        com.truckhire.modules.booking.entity.Booking booking = txn.getBooking();
        com.truckhire.modules.truck.entity.Truck truck = booking.getTruck();
        User renter = booking.getRenter();
        User owner = booking.getOwner();

        PlatformSettings settings = platformSettingsService.getSettings();

        // Build owner info block — always present; bank fields null if owner has not linked a bank account
        String maskedAccount = null;
        if (owner.getBankAccountNumber() != null) {
            String raw = owner.getBankAccountNumber();
            maskedAccount = raw.length() > 4 ? "****" + raw.substring(raw.length() - 4) : "****";
        }
        com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo ownerInfo =
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo.builder()
                        .fullname(owner.getFullname())
                        .stripeConnected(owner.getStripeAccountId() != null)
                        .bankName(owner.getBankAccountNumber() != null ? owner.getBankName() : null)
                        .accountNumber(maskedAccount)
                        .routingNumber(owner.getBankAccountNumber() != null ? owner.getBankRoutingNumber() : null)
                        .build();

        boolean isMileage = txn.getType() == PaymentType.MILEAGE_TOPUP;

        return com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.builder()
                .id(txn.getId().toString())
                .invoiceNumber(txn.getInvoiceNumber())
                .bookingNumber(booking.getBookingNumber())
                .paymentDate(txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null)
                .gateway(txn.getGateway().name())
                .invoiceType(isMileage ? "MILEAGE" : "DAY_RATE")
                .paymentStatus(txn.getStatus().name())
                .settlementStatus(resolveSettlementStatus(txn))
                .total(txn.getAmount())
                .ownerShare(txn.getOwnerAmount())
                .platformShare(txn.getPlatformFee())
                .ownerSharePercent(txn.getPlatformFee() != null
                        ? java.math.BigDecimal.valueOf(100).subtract(settings.getPlatformFeePercent())
                        : null)
                .platformFeePercent(settings.getPlatformFeePercent())
                .transactionId(txn.getGatewayPaymentId())
                .paymentMethod(txn.getPaymentMethod())
                .cardLast4(txn.getCardLast4())
                // Mileage breakdown — populated only for MILEAGE invoices
                .milesDriven(isMileage ? booking.getOdometerEnd() != null && booking.getOdometerStart() != null
                        ? booking.getOdometerEnd() - booking.getOdometerStart() : null : null)
                .costPerMile(isMileage ? booking.getCostPerMile() : null)
                .dayAmount(isMileage ? booking.getDayAmount() : null)
                .mileageAmount(isMileage ? booking.getMileageAmount() : null)
                .totalBookingAmount(isMileage ? booking.getTotalAmount() : null)
                .booking(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.BookingDetail.builder()
                        .id(booking.getId().toString())
                        .startDate(booking.getStartDate().toString())
                        .endDate(booking.getEndDate().toString())
                        .pickupLocation(booking.getPickupLocation())
                        .dropoffLocation(booking.getDropoffLocation())
                        .insuranceCost(booking.getInsuranceCost())
                        .additionalServicesCost(booking.getAdditionalServicesCost())
                        .tax(booking.getTax())
                        .renter(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.RenterInfo.builder()
                                .fullname(renter.getFullname())
                                .email(renter.getEmail())
                                .phone(renter.getPhone())
                                .build())
                        .owner(ownerInfo)
                        .truck(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.TruckInfo.builder()
                                .model(truck.getModel())
                                .pricePerDay(booking.getPricePerDay())
                                .registrationNumber(truck.getRegistrationNumber())
                                .locationCity(truck.getLocationCity())
                                .build())
                        .build())
                .build();
    }

    /**
     * Payout shortcut by payment transaction ID.
     * Looks up the transaction, extracts its booking ID, and delegates to initiateOwnerPayout.
     * Used by POST /admin/payments/{id}/payout so the admin can trigger payout directly
     * from the invoice detail page without needing to know the booking ID.
     */
    @Transactional
    public void initiateOwnerPayoutByTransactionId(UUID transactionId) {
        PaymentTransaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("PaymentTransaction", "id", transactionId));
        if (txn.getType() != PaymentType.CHARGE && txn.getType() != PaymentType.MILEAGE_TOPUP) {
            throw new BusinessException("INVALID_TRANSACTION_TYPE",
                    "Payout can only be initiated from a payment invoice (CHARGE or MILEAGE_TOPUP).");
        }
        initiateOwnerPayout(txn.getBooking().getId());
    }

    private com.truckhire.modules.payment.dto.AdminPaymentListResponse mapToAdminPaymentListResponse(
            PaymentTransaction txn) {
        com.truckhire.modules.booking.entity.Booking booking = txn.getBooking();
        return com.truckhire.modules.payment.dto.AdminPaymentListResponse.builder()
                .id(txn.getId().toString())
                .invoiceNumber(txn.getInvoiceNumber())
                .bookingNumber(booking.getBookingNumber())
                .renterName(booking.getRenter().getFullname())
                .paymentDate(txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null)
                .invoiceType(txn.getType() == PaymentType.MILEAGE_TOPUP ? "MILEAGE" : "DAY_RATE")
                .total(txn.getAmount())
                .ownerShare(txn.getOwnerAmount())
                .platformShare(txn.getPlatformFee())
                .paymentStatus(txn.getStatus().name())
                .settlementStatus(resolveSettlementStatus(txn))
                .build();
    }

    // ═══════════════════════════════════════
    // GROUPED INVOICE VIEW (BY BOOKING)
    // ═══════════════════════════════════════

    /**
     * Returns one row per booking combining the CHARGE txn with its optional MILEAGE_TOPUP.
     * Supports the same search + settlementStatus filters as the flat invoice list.
     */
    @Transactional(readOnly = true)
    public com.truckhire.common.dto.PagedResponse<com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse>
            getAdminPaymentsByBooking(String search, String settlementStatus,
                                     org.springframework.data.domain.Pageable pageable) {

        boolean hasSearch = search != null && !search.isBlank();
        boolean hasStatus = settlementStatus != null && !settlementStatus.isBlank();

        PaymentStatus statusFilter = null;
        if (hasStatus) {
            try {
                statusFilter = PaymentStatus.valueOf(settlementStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                hasStatus = false;
            }
        }

        org.springframework.data.domain.Page<PaymentTransaction> page;
        if (hasSearch && hasStatus) {
            String q = "%" + search.toLowerCase().trim() + "%";
            page = transactionRepository.searchChargeOnlyByStatus(q, statusFilter, pageable);
        } else if (hasSearch) {
            String q = "%" + search.toLowerCase().trim() + "%";
            page = transactionRepository.searchChargeOnlyWithDetails(q, pageable);
        } else if (hasStatus) {
            page = transactionRepository.findChargeOnlyByStatus(statusFilter, pageable);
        } else {
            page = transactionRepository.findAllChargeOnlyWithDetails(pageable);
        }

        // Batch-fetch mileage transactions for all bookings on this page
        java.util.List<java.util.UUID> bookingIds = page.getContent().stream()
                .map(t -> t.getBooking().getId())
                .collect(java.util.stream.Collectors.toList());

        java.util.Map<java.util.UUID, PaymentTransaction> mileageByBookingId = new java.util.HashMap<>();
        if (!bookingIds.isEmpty()) {
            transactionRepository.findMileageByBookingIds(bookingIds)
                    .forEach(m -> mileageByBookingId.put(m.getBooking().getId(), m));
        }

        java.util.List<com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse> content =
                page.getContent().stream()
                        .map(charge -> mapToBookingInvoiceResponse(charge, mileageByBookingId.get(charge.getBooking().getId())))
                        .collect(java.util.stream.Collectors.toList());

        return com.truckhire.common.dto.PagedResponse.<com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    private com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse mapToBookingInvoiceResponse(
            PaymentTransaction charge, PaymentTransaction mileage) {

        com.truckhire.modules.booking.entity.Booking booking = charge.getBooking();
        User renter = booking.getRenter();

        boolean hasMileage = mileage != null;
        java.math.BigDecimal mileageAmount = hasMileage ? mileage.getAmount() : null;
        java.math.BigDecimal totalAmount = hasMileage
                ? charge.getAmount().add(mileage.getAmount())
                : charge.getAmount();

        java.math.BigDecimal ownerShare = charge.getOwnerAmount() != null ? charge.getOwnerAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal platformShare = charge.getPlatformFee() != null ? charge.getPlatformFee() : java.math.BigDecimal.ZERO;
        if (hasMileage) {
            if (mileage.getOwnerAmount() != null) ownerShare = ownerShare.add(mileage.getOwnerAmount());
            if (mileage.getPlatformFee() != null) platformShare = platformShare.add(mileage.getPlatformFee());
        }

        // Payment status: derived from charge + optional mileage txn statuses
        String paymentStatus;
        if (charge.getStatus() != PaymentStatus.SUCCEEDED) {
            paymentStatus = "PENDING";
        } else if (hasMileage && mileage.getStatus() != PaymentStatus.SUCCEEDED) {
            paymentStatus = "PARTIALLY_PAID";
        } else {
            paymentStatus = "FULLY_PAID";
        }

        return com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse.builder()
                .bookingId(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .renterName(renter != null ? renter.getFullname() : null)
                .chargeInvoiceNumber(charge.getInvoiceNumber())
                .mileageInvoiceNumber(hasMileage ? mileage.getInvoiceNumber() : null)
                .paymentDate(charge.getCreatedAt() != null ? charge.getCreatedAt().toString() : null)
                .gateway(charge.getGateway() != null ? charge.getGateway().name() : null)
                .dayAmount(charge.getAmount())
                .mileageAmount(mileageAmount)
                .totalAmount(totalAmount)
                .ownerShare(ownerShare)
                .platformShare(platformShare)
                .paymentStatus(paymentStatus)
                .settlementStatus(resolveSettlementStatus(charge))
                .hasMileage(hasMileage)
                .build();
    }

    private String resolveSettlementStatus(PaymentTransaction txn) {
        // Check if there's a payout transaction for this booking
        Optional<PaymentTransaction> payout = transactionRepository
                .findFirstByBookingIdAndTypeOrderByCreatedAtDesc(txn.getBooking().getId(), PaymentType.PAYOUT);
        if (payout.isEmpty()) return "PENDING";
        return payout.get().getStatus() == PaymentStatus.PAID_OUT ? "SETTLED" : "PAYOUT_PENDING";
    }

    // ═══════════════════════════════════════
    // MY PAYMENT HISTORY (RENTER + OWNER)
    // ═══════════════════════════════════════

    /**
     * Returns payment history for the current user.
     * - RENTER: their outgoing CHARGE + REFUND transactions
     * - OWNER:  their incoming PAYOUT transactions
     * Role is detected from the User entity passed in — no branching on JWT in service layer.
     */
    @Transactional(readOnly = true)
    public com.truckhire.common.dto.PagedResponse<com.truckhire.modules.payment.dto.MyPaymentHistoryResponse>
            getMyPayments(User currentUser, org.springframework.data.domain.Pageable pageable) {

        org.springframework.data.domain.Page<PaymentTransaction> page;
        String roleName = currentUser.getRole().getName();

        if ("OWNER".equals(roleName)) {
            page = transactionRepository.findOwnerPayoutHistory(currentUser.getId(), pageable);
        } else {
            // RENTER (and fallback for any other role)
            page = transactionRepository.findRenterPaymentHistory(currentUser.getId(), pageable);
        }

        java.util.List<com.truckhire.modules.payment.dto.MyPaymentHistoryResponse> content =
                page.getContent().stream()
                        .map(this::mapToMyPaymentHistoryResponse)
                        .collect(java.util.stream.Collectors.toList());

        return com.truckhire.common.dto.PagedResponse.<com.truckhire.modules.payment.dto.MyPaymentHistoryResponse>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    private com.truckhire.modules.payment.dto.MyPaymentHistoryResponse mapToMyPaymentHistoryResponse(
            PaymentTransaction txn) {
        com.truckhire.modules.booking.entity.Booking booking = txn.getBooking();
        com.truckhire.modules.truck.entity.Truck truck = booking.getTruck();

        com.truckhire.modules.payment.dto.MyPaymentHistoryResponse.MyPaymentHistoryResponseBuilder builder =
                com.truckhire.modules.payment.dto.MyPaymentHistoryResponse.builder()
                .id(txn.getId().toString())
                .bookingId(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .truckId(truck != null ? truck.getId().toString() : null)
                .truckModel(truck != null ? truck.getModel() : null)
                .amount(txn.getAmount())
                .status(txn.getStatus().name())
                .type(txn.getType().name())
                .gateway(txn.getGateway().name())
                .currency(txn.getCurrency())
                .createdAt(txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null);

        // ── Owner-only enrichment — only for PAYOUT transactions ──
        if (txn.getType() == PaymentType.PAYOUT) {
            builder.startDate(booking.getStartDate() != null ? booking.getStartDate().toString() : null)
                   .endDate(booking.getEndDate() != null ? booking.getEndDate().toString() : null)
                   .failureReason(txn.getFailureReason());

            // grossAmount — use booking.totalAmount if set (includes mileage + day rate),
            // otherwise fall back to the CHARGE transaction amount (day rate only).
            // totalAmount is set when booking transitions to COMPLETED (after odometer_end).
            if (booking.getTotalAmount() != null) {
                builder.grossAmount(booking.getTotalAmount());
            } else {
                transactionRepository.findByBookingIdAndTypeAndStatus(
                        booking.getId(), PaymentType.CHARGE, PaymentStatus.SUCCEEDED)
                        .ifPresent(charge -> builder.grossAmount(charge.getAmount()));
            }

            // platformFeePercent — from current platform settings
            PlatformSettings settings = platformSettingsService.getSettings();
            builder.platformFeePercent(settings.getPlatformFeePercent());
        }

        return builder.build();
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
