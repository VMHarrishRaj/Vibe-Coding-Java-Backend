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
    private final com.truckhire.modules.truck.repository.TruckDocumentRepository truckDocumentRepository;

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
        // Idempotency: a refund row already exists — do not hit the gateway a second time
        if (transactionRepository.existsByBookingIdAndType(bookingId, PaymentType.REFUND)) {
            log.info("Refund already issued for bookingId={} — skipping", bookingId);
            return;
        }

        // If payment was initiated but never captured, mark it CANCELLED — no gateway call needed
        Optional<PaymentTransaction> pendingCharge = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.CHARGE, PaymentStatus.PENDING);
        if (pendingCharge.isPresent()) {
            pendingCharge.get().setStatus(PaymentStatus.CANCELLED);
            transactionRepository.save(pendingCharge.get());
            log.info("Pending charge voided on cancellation: bookingId={}", bookingId);
            return;
        }

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
     * Admin-initiated payout to the owner after a booking is fully settled.
     *
     * Calculates:
     * - platformFee = totalAmount * (platformFeePercent / 100)
     * - ownerAmount = totalAmount - platformFee
     *
     * Then initiates a transfer to the owner's linked gateway account.
     * If the owner has no gateway account linked, records a PAYOUT_PENDING transaction
     * so the admin can see the pending payout and retry it via POST /admin/bookings/{id}/payout.
     *
     * Guards (in order):
     * 1. No existing payout (PAYOUT_PENDING or PAID_OUT) — prevents double payout
     * 2. CHARGE/SUCCEEDED must exist — day rate must be collected
     * 3. No MILEAGE_TOPUP/PENDING — mileage charge must be collected before payout
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

        // Guard: block payout if the renter has not yet paid the mileage charge.
        // booking.totalAmount is set at odometer return and already includes mileage,
        // but the MILEAGE_TOPUP payment may still be PENDING (renter hasn't paid yet).
        // Paying out before collecting mileage means the owner receives money the
        // platform hasn't actually collected.
        Optional<PaymentTransaction> pendingMileage = transactionRepository
                .findByBookingIdAndTypeAndStatus(bookingId, PaymentType.MILEAGE_TOPUP, PaymentStatus.PENDING);
        if (pendingMileage.isPresent()) {
            throw new BusinessException("MILEAGE_PAYMENT_PENDING",
                    "Cannot initiate payout: the renter has not yet paid the mileage charge. " +
                    "Wait for the mileage payment to complete before releasing funds to the owner.");
        }

        PaymentTransaction charge = chargeOpt.get();
        Booking booking = charge.getBooking();
        User owner = booking.getOwner();

        // Use totalAmount (dayAmount + mileageAmount, set on COMPLETED) if available.
        // By the time we reach here, mileage has already been collected (guarded above),
        // so totalAmount is the correct full amount to pay out.
        // Falls back to dayAmount for bookings where no mileage was driven (totalAmount
        // remains null when costPerMile is zero or milesDriven is zero).
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

        long invoiceSeq = transactionRepository.nextInvoiceSequence();
        String invoiceNumber = String.format("INV%03d", invoiceSeq);

        PaymentTransaction txn = PaymentTransaction.builder()
                .booking(booking)
                .gateway(gateway)
                .gatewayOrderId(gatewayOrderId)
                .amount(mileageAmount)
                .currency(currency)
                .status(PaymentStatus.PENDING)
                .type(PaymentType.MILEAGE_TOPUP)
                .invoiceNumber(invoiceNumber)
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
        // Fetch payout transaction to populate settlement fields
        java.util.Optional<PaymentTransaction> payoutTxn = transactionRepository
                .findFirstByBookingIdAndTypeOrderByCreatedAtDesc(booking.getId(), PaymentType.PAYOUT);

        com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo ownerInfo =
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo.builder()
                        .fullname(owner.getFullname())
                        .email(owner.getEmail())
                        .phone(owner.getPhone())
                        .stripeConnected(owner.getStripeAccountId() != null)
                        .bankName(owner.getBankAccountNumber() != null ? owner.getBankName() : null)
                        .accountNumber(maskedAccount)
                        .routingNumber(owner.getBankAccountNumber() != null ? owner.getBankRoutingNumber() : null)
                        .settlementId(payoutTxn.map(PaymentTransaction::getGatewayTransferId).orElse(null))
                        .settlementDate(payoutTxn.map(p -> p.getCreatedAt() != null ? p.getCreatedAt().toString() : null).orElse(null))
                        .settlementMethod(payoutTxn.map(p -> p.getGateway() != null ? p.getGateway().name() : null).orElse(null))
                        .build();

        boolean isMileage = txn.getType() == PaymentType.MILEAGE_TOPUP;

        // When called with a CHARGE transaction, fetch the paired MILEAGE_TOPUP (if any) and build combined summary
        com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.MileageDetail mileageDetail = null;
        com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.CombinedSummary combinedSummary = null;

        if (!isMileage) {
            java.util.Optional<PaymentTransaction> pairedMileage =
                    transactionRepository.findMileageByBookingId(booking.getId());

            java.math.BigDecimal mileageAmt = java.math.BigDecimal.ZERO;
            java.math.BigDecimal mileageOwnerShare = java.math.BigDecimal.ZERO;
            java.math.BigDecimal mileagePlatformShare = java.math.BigDecimal.ZERO;

            if (pairedMileage.isPresent()) {
                PaymentTransaction m = pairedMileage.get();
                Integer milesDriven = (booking.getOdometerEnd() != null && booking.getOdometerStart() != null)
                        ? booking.getOdometerEnd() - booking.getOdometerStart() : null;
                mileageAmt = m.getAmount() != null ? m.getAmount() : java.math.BigDecimal.ZERO;
                mileageOwnerShare = m.getOwnerAmount() != null ? m.getOwnerAmount() : java.math.BigDecimal.ZERO;
                mileagePlatformShare = m.getPlatformFee() != null ? m.getPlatformFee() : java.math.BigDecimal.ZERO;

                mileageDetail = com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.MileageDetail.builder()
                        .id(m.getId().toString())
                        .invoiceNumber(m.getInvoiceNumber())
                        .paymentDate(m.getCreatedAt() != null ? m.getCreatedAt().toString() : null)
                        .paymentStatus(m.getStatus().name())
                        .displayPaymentStatus(toDisplayPaymentStatus(m.getStatus().name()))
                        .settlementStatus(resolveSettlementStatus(m))
                        .displaySettlementStatus(toDisplaySettlementStatus(resolveSettlementStatus(m)))
                        .milesDriven(milesDriven)
                        .costPerMile(booking.getCostPerMile())
                        .mileageAmount(mileageAmt)
                        .ownerShare(m.getOwnerAmount())
                        .platformShare(m.getPlatformFee())
                        .build();
            }

            java.math.BigDecimal insuranceCost = booking.getInsuranceCost() != null
                    ? booking.getInsuranceCost() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal additionalServicesCost = booking.getAdditionalServicesCost() != null
                    ? booking.getAdditionalServicesCost() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal chargeOwnerShare = txn.getOwnerAmount() != null ? txn.getOwnerAmount() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal chargePlatformShare = txn.getPlatformFee() != null ? txn.getPlatformFee() : java.math.BigDecimal.ZERO;

            combinedSummary = com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.CombinedSummary.builder()
                    .dayAmount(txn.getAmount())
                    .mileageAmount(mileageAmt)
                    .insuranceCost(booking.getInsuranceCost())
                    .additionalServicesCost(booking.getAdditionalServicesCost())
                    .totalPaid(txn.getAmount().add(mileageAmt).add(insuranceCost).add(additionalServicesCost))
                    .ownerShare(chargeOwnerShare.add(mileageOwnerShare))
                    .platformShare(chargePlatformShare.add(mileagePlatformShare))
                    .build();
        }

        return com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.builder()
                .id(txn.getId().toString())
                .invoiceNumber(txn.getInvoiceNumber())
                .bookingNumber(booking.getBookingNumber())
                .paymentDate(txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null)
                .gateway(txn.getGateway().name())
                .invoiceType(isMileage ? "MILEAGE" : "DAY_RATE")
                .paymentStatus(txn.getStatus().name())
                .displayPaymentStatus(toDisplayPaymentStatus(txn.getStatus().name()))
                .settlementStatus(resolveSettlementStatus(txn))
                .displaySettlementStatus(toDisplaySettlementStatus(resolveSettlementStatus(txn)))
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
                .mileageTransaction(mileageDetail)
                .combinedSummary(combinedSummary)
                .invoices(buildInvoiceEntries(txn, mileageDetail))
                .booking(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.BookingDetail.builder()
                        .id(booking.getId().toString())
                        .startDate(booking.getStartDate().toLocalDate().toString())
                        .endDate(booking.getEndDate().toLocalDate().toString())
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
                .displayPaymentStatus(toDisplayPaymentStatus(txn.getStatus().name()))
                .settlementStatus(resolveSettlementStatus(txn))
                .displaySettlementStatus(toDisplaySettlementStatus(resolveSettlementStatus(txn)))
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
    public com.truckhire.modules.payment.dto.AdminPaymentsByBookingResponse
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

        com.truckhire.common.dto.PagedResponse<com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse> pagedResponse =
                com.truckhire.common.dto.PagedResponse.<com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse>builder()
                        .content(content)
                        .pageNumber(page.getNumber())
                        .pageSize(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .last(page.isLast())
                        .build();

        // Widget stats — computed globally (not filtered by page/search) to always show platform-wide totals
        // totalPaid = actual money captured (SUCCEEDED)
        // totalPending = bookings not yet paid (PENDING)
        // totalRevenue = Gross Expected Revenue (SUCCEEDED + PENDING)
        java.math.BigDecimal totalPaid     = transactionRepository.sumTotalPaidCharges();
        java.math.BigDecimal totalPending  = transactionRepository.sumPendingCharges();
        java.math.BigDecimal totalRevenue  = totalPaid.add(totalPending);
        
        java.math.BigDecimal platformShare = transactionRepository.sumPlatformFees();
        java.math.BigDecimal ownerShare    = transactionRepository.sumSettledOwnerPayouts();

        return com.truckhire.modules.payment.dto.AdminPaymentsByBookingResponse.builder()
                .totalRevenue(totalRevenue)
                .totalPaid(totalPaid)
                .totalPending(totalPending)
                .totalPlatformShare(platformShare)
                .totalOwnerShare(ownerShare)
                .invoices(pagedResponse)
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
        } else if (booking.getStatus() != com.truckhire.modules.booking.entity.BookingStatus.COMPLETED &&
                   booking.getStatus() != com.truckhire.modules.booking.entity.BookingStatus.CANCELLED &&
                   booking.getStatus() != com.truckhire.modules.booking.entity.BookingStatus.REJECTED) {
            // Day charge paid, but mileage hasn't been evaluated yet
            paymentStatus = "PARTIALLY_PAID";
        } else if (hasMileage && mileage.getStatus() != PaymentStatus.SUCCEEDED) {
            paymentStatus = "PARTIALLY_PAID";
        } else {
            paymentStatus = "FULLY_PAID";
        }

        return com.truckhire.modules.payment.dto.AdminBookingInvoiceResponse.builder()
                .bookingId(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .renterName(renter != null ? renter.getFullname() : null)
                .chargeTransactionId(charge.getId().toString())
                .mileageTransactionId(hasMileage ? mileage.getId().toString() : null)
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
                .displayPaymentStatus(toDisplayPaymentStatus(paymentStatus.equals("FULLY_PAID") ? "SUCCEEDED" : paymentStatus))
                .settlementStatus(resolveSettlementStatus(charge))
                .displaySettlementStatus(toDisplaySettlementStatus(resolveSettlementStatus(charge)))
                .hasMileage(hasMileage)
                .build();
    }

    private java.util.List<com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.InvoiceEntry> buildInvoiceEntries(
            PaymentTransaction charge,
            com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.MileageDetail mileageDetail) {

        java.util.List<com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.InvoiceEntry> entries = new java.util.ArrayList<>();

        // Mileage first (most recent) — only if it exists
        if (mileageDetail != null) {
            entries.add(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.InvoiceEntry.builder()
                    .id(mileageDetail.getId())
                    .invoiceNumber(mileageDetail.getInvoiceNumber())  // e.g. INV021
                    .invoiceType("MILEAGE")
                    .paymentDate(mileageDetail.getPaymentDate())
                    .paymentStatus(mileageDetail.getPaymentStatus())
                    .displayPaymentStatus(mileageDetail.getDisplayPaymentStatus())
                    .settlementStatus(mileageDetail.getSettlementStatus())
                    .displaySettlementStatus(mileageDetail.getDisplaySettlementStatus())
                    .amount(mileageDetail.getMileageAmount())
                    .ownerShare(mileageDetail.getOwnerShare())
                    .platformShare(mileageDetail.getPlatformShare())
                    .transactionId(null)
                    .build());
        }

        // Day-rate charge always present
        entries.add(com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.InvoiceEntry.builder()
                .id(charge.getId().toString())
                .invoiceNumber(charge.getInvoiceNumber())
                .invoiceType("DAY_RATE")
                .paymentDate(charge.getCreatedAt() != null ? charge.getCreatedAt().toString() : null)
                .paymentStatus(charge.getStatus().name())
                .displayPaymentStatus(toDisplayPaymentStatus(charge.getStatus().name()))
                .settlementStatus(resolveSettlementStatus(charge))
                .displaySettlementStatus(toDisplaySettlementStatus(resolveSettlementStatus(charge)))
                .amount(charge.getAmount())
                .ownerShare(charge.getOwnerAmount())
                .platformShare(charge.getPlatformFee())
                .transactionId(charge.getGatewayPaymentId())
                .build());

        return entries;
    }

    private String resolveSettlementStatus(PaymentTransaction txn) {
        // Check if there's a payout transaction for this booking
        Optional<PaymentTransaction> payout = transactionRepository
                .findFirstByBookingIdAndTypeOrderByCreatedAtDesc(txn.getBooking().getId(), PaymentType.PAYOUT);
        if (payout.isEmpty()) return "PENDING";
        return payout.get().getStatus() == PaymentStatus.PAID_OUT ? "SETTLED" : "PAYOUT_PENDING";
    }

    private String toDisplayPaymentStatus(String rawStatus) {
        return switch (rawStatus) {
            case "SUCCEEDED" -> "Received";
            case "FAILED"    -> "Failed";
            case "REFUNDED"  -> "Refunded";
            default          -> "Pending";
        };
    }

    private String toDisplaySettlementStatus(String rawStatus) {
        return switch (rawStatus) {
            case "SETTLED"       -> "Settled";
            case "PAYOUT_PENDING" -> "Settlement Pending";
            default              -> "Pending";
        };
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

        // For PAYOUT transactions the invoiceNumber on the row itself is null (payouts are not
        // invoiced separately). Surface the paired CHARGE transaction's invoiceNumber instead —
        // that is the invoice the renter paid and what the owner recognises as the booking invoice.
        // For CHARGE / MILEAGE_TOPUP / REFUND rows the invoiceNumber is on the transaction itself.
        String invoiceNumber;
        if (txn.getType() == PaymentType.PAYOUT) {
            invoiceNumber = transactionRepository
                    .findByBookingIdAndTypeAndStatus(booking.getId(), PaymentType.CHARGE, PaymentStatus.SUCCEEDED)
                    .map(PaymentTransaction::getInvoiceNumber)
                    .orElse(null);
        } else {
            invoiceNumber = txn.getInvoiceNumber();
        }

        com.truckhire.modules.payment.dto.MyPaymentHistoryResponse.MyPaymentHistoryResponseBuilder builder =
                com.truckhire.modules.payment.dto.MyPaymentHistoryResponse.builder()
                .id(txn.getId().toString())
                .bookingId(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .invoiceNumber(invoiceNumber)
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
            builder.startDate(booking.getStartDate() != null ? booking.getStartDate().toLocalDate().toString() : null)
                   .endDate(booking.getEndDate() != null ? booking.getEndDate().toLocalDate().toString() : null)
                   .renterName(booking.getRenter() != null ? booking.getRenter().getFullname() : null)
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

    // ═══════════════════════════════════════
    // RENTER PAYMENT HISTORY — BOOKING-GROUPED
    // ═══════════════════════════════════════

    /**
     * Returns booking-grouped payment history for a RENTER.
     *
     * All CHARGE + MILEAGE_TOPUP + REFUND rows for the renter are fetched in one query,
     * then grouped by booking in memory. Each booking group computes a single displayStatus
     * derived from the business outcome — not from any individual transaction's raw status.
     * The invoices list inside each group carries the raw rows for expanded card views.
     *
     * Pagination is applied to the booking groups (not the raw rows).
     */
    @Transactional(readOnly = true)
    public com.truckhire.modules.payment.dto.RenterPaymentPageResponse
            getRenterPaymentHistory(User renter, org.springframework.data.domain.Pageable pageable) {

        java.util.List<PaymentTransaction> all =
                transactionRepository.findRenterTransactionsForGrouping(renter.getId());

        // Group by booking, preserving insertion order (query ordered by b.createdAt DESC)
        java.util.LinkedHashMap<java.util.UUID, java.util.List<PaymentTransaction>> byBooking =
                new java.util.LinkedHashMap<>();
        for (PaymentTransaction t : all) {
            byBooking.computeIfAbsent(t.getBooking().getId(), k -> new java.util.ArrayList<>()).add(t);
        }

        // Build one RenterPaymentGroupResponse per booking
        java.util.List<com.truckhire.modules.payment.dto.RenterPaymentGroupResponse> allGroups =
                byBooking.values().stream()
                        .map(this::mapToRenterPaymentGroup)
                        .collect(java.util.stream.Collectors.toList());

        // Compute totals across ALL groups (before pagination slice)
        java.math.BigDecimal totalPaid = java.math.BigDecimal.ZERO;
        java.math.BigDecimal thisMonth = java.math.BigDecimal.ZERO;
        java.math.BigDecimal pending   = java.math.BigDecimal.ZERO;
        java.time.YearMonth currentMonth = java.time.YearMonth.now();

        for (com.truckhire.modules.payment.dto.RenterPaymentGroupResponse g : allGroups) {
            if ("SUCCEEDED".equals(g.getDisplayStatus()) || "REFUNDED".equals(g.getDisplayStatus())) {
                totalPaid = totalPaid.add(g.getDisplayAmount());
            }
            if ("PENDING".equals(g.getDisplayStatus())) {
                pending = pending.add(g.getDisplayAmount());
            }
            if (g.getPaidAt() != null) {
                try {
                    java.time.Instant paidInstant = java.time.Instant.parse(g.getPaidAt());
                    java.time.YearMonth paidMonth = java.time.YearMonth.from(
                            paidInstant.atZone(java.time.ZoneOffset.UTC));
                    if (currentMonth.equals(paidMonth)) {
                        thisMonth = thisMonth.add(g.getDisplayAmount());
                    }
                } catch (Exception ignored) {}
            }
        }

        // Manual pagination of the groups list
        int pageNum  = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        int total    = allGroups.size();
        int fromIdx  = Math.min(pageNum * pageSize, total);
        int toIdx    = Math.min(fromIdx + pageSize, total);
        java.util.List<com.truckhire.modules.payment.dto.RenterPaymentGroupResponse> pageContent =
                allGroups.subList(fromIdx, toIdx);

        com.truckhire.modules.payment.dto.RenterPaymentTotals totals =
                com.truckhire.modules.payment.dto.RenterPaymentTotals.builder()
                        .totalPaid(totalPaid)
                        .totalTransactions(total)
                        .thisMonth(thisMonth)
                        .pending(pending)
                        .build();

        com.truckhire.common.dto.PagedResponse<com.truckhire.modules.payment.dto.RenterPaymentGroupResponse> page =
                com.truckhire.common.dto.PagedResponse.<com.truckhire.modules.payment.dto.RenterPaymentGroupResponse>builder()
                        .content(pageContent)
                        .pageNumber(pageNum)
                        .pageSize(pageSize)
                        .totalElements(total)
                        .totalPages((int) Math.ceil((double) total / pageSize))
                        .last(toIdx >= total)
                        .build();

        return com.truckhire.modules.payment.dto.RenterPaymentPageResponse.builder()
                .payments(page)
                .totals(totals)
                .build();
    }

    private com.truckhire.modules.payment.dto.RenterPaymentGroupResponse mapToRenterPaymentGroup(
            java.util.List<PaymentTransaction> txns) {

        // The booking is the same on all rows in this group
        com.truckhire.modules.booking.entity.Booking booking = txns.get(0).getBooking();
        com.truckhire.modules.truck.entity.Truck truck = booking.getTruck();

        PaymentTransaction charge     = null;
        PaymentTransaction mileage    = null;
        PaymentTransaction refundRow  = null;

        for (PaymentTransaction t : txns) {
            switch (t.getType()) {
                case CHARGE       -> charge    = t;
                case MILEAGE_TOPUP -> mileage  = t;
                case REFUND       -> refundRow = t;
                default           -> {}
            }
        }

        // ── Compute displayStatus from business outcome ──
        // Priority: if a REFUND succeeded → REFUNDED regardless of charge row status.
        // Otherwise read the charge row's status directly.
        String displayStatus;
        if (refundRow != null && refundRow.getStatus() == PaymentStatus.SUCCEEDED) {
            displayStatus = "REFUNDED";
        } else if (charge != null) {
            displayStatus = switch (charge.getStatus()) {
                case SUCCEEDED -> "SUCCEEDED";
                case FAILED    -> "FAILED";
                default        -> "PENDING";
            };
        } else {
            displayStatus = "PENDING";
        }

        // ── Compute displayAmount (net the renter actually paid) ──
        // SUCCEEDED: sum of charge + mileage amounts
        // REFUNDED:  0 (full refund — we confirmed no partial refund scenario)
        // PENDING/FAILED: charge amount (what will be / was attempted)
        java.math.BigDecimal chargeAmt  = charge  != null ? charge.getAmount()  : java.math.BigDecimal.ZERO;
        java.math.BigDecimal mileageAmt = mileage != null ? mileage.getAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal displayAmount;
        if ("REFUNDED".equals(displayStatus)) {
            displayAmount = java.math.BigDecimal.ZERO;
        } else {
            displayAmount = chargeAmt.add(mileageAmt);
        }

        // ── paidAt: ISO timestamp of the CHARGE transaction ──
        String paidAt = null;
        if (charge != null && charge.getCreatedAt() != null
                && charge.getStatus() == PaymentStatus.SUCCEEDED) {
            paidAt = charge.getCreatedAt().toString();
        }

        // ── Truck cover photo — first photo uploaded ──
        String coverPhotoUrl = null;
        if (truck != null) {
            java.util.List<com.truckhire.modules.truck.entity.TruckDocument> photos =
                    truckDocumentRepository.findAllPhotosByTruckId(truck.getId());
            if (!photos.isEmpty()) {
                coverPhotoUrl = baseUrl + "/files/" + photos.get(0).getFilePath();
            }
        }

        // ── Build invoices list ──
        java.util.List<com.truckhire.modules.payment.dto.RenterPaymentInvoiceItem> invoices =
                txns.stream()
                        .map(t -> {
                            String label = switch (t.getType()) {
                                case CHARGE        -> "Day Rate";
                                case MILEAGE_TOPUP -> "Mileage Charge";
                                case REFUND        -> "Refund";
                                default            -> t.getType().name();
                            };
                            boolean downloadable = t.getType() == PaymentType.CHARGE
                                    || t.getType() == PaymentType.MILEAGE_TOPUP;
                            return com.truckhire.modules.payment.dto.RenterPaymentInvoiceItem.builder()
                                    .id(t.getId().toString())
                                    .type(t.getType().name())
                                    .label(label)
                                    .amount(t.getAmount())
                                    .status(t.getStatus().name())
                                    .invoiceNumber(t.getInvoiceNumber())
                                    .downloadable(downloadable)
                                    .createdAt(t.getCreatedAt() != null ? t.getCreatedAt().toString() : null)
                                    .build();
                        })
                        .collect(java.util.stream.Collectors.toList());

        String truckModel = truck != null
                ? (truck.getMake() != null ? truck.getMake() + " " : "") + truck.getModel()
                : null;

        return com.truckhire.modules.payment.dto.RenterPaymentGroupResponse.builder()
                .bookingId(booking.getId().toString())
                .bookingNumber(booking.getBookingNumber())
                .truckModel(truckModel)
                .truckRegistration(truck != null ? truck.getRegistrationNumber() : null)
                .truckCoverPhotoUrl(coverPhotoUrl)
                .startDate(booking.getStartDate() != null ? booking.getStartDate().toLocalDate().toString() : null)
                .endDate(booking.getEndDate() != null ? booking.getEndDate().toLocalDate().toString() : null)
                .displayStatus(displayStatus)
                .displayAmount(displayAmount)
                .currency(charge != null ? charge.getCurrency() : null)
                .gateway(charge != null ? charge.getGateway().name() : null)
                .paidAt(paidAt)
                .chargeInvoiceId(charge != null ? charge.getId().toString() : null)
                .chargeInvoiceNumber(charge != null ? charge.getInvoiceNumber() : null)
                .mileageInvoiceId(mileage != null ? mileage.getId().toString() : null)
                .mileageInvoiceNumber(mileage != null ? mileage.getInvoiceNumber() : null)
                .invoices(invoices)
                .build();
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

    // ═══════════════════════════════════════
    // INVOICE PDF DOWNLOAD
    // ═══════════════════════════════════════

    /**
     * Generates a PDF invoice for a CHARGE transaction and returns it as a byte array.
     * Used by GET /admin/payments/{id}/download.
     */
    @Transactional(readOnly = true)
    public byte[] generateInvoicePdf(UUID transactionId) {
        com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse detail = getAdminInvoiceDetail(transactionId);

        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            com.lowagie.text.Document doc = new com.lowagie.text.Document(com.lowagie.text.PageSize.A4, 50, 50, 60, 50);
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.open();

            // ── Fonts ──
            com.lowagie.text.Font brandFont   = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 22, com.lowagie.text.Font.BOLD,  new java.awt.Color(30, 64, 175));
            com.lowagie.text.Font invoiceLabel= new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 11, com.lowagie.text.Font.NORMAL, new java.awt.Color(100, 116, 139));
            com.lowagie.text.Font metaKey     = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.NORMAL, new java.awt.Color(100, 116, 139));
            com.lowagie.text.Font metaVal     = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font sectionHead = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.BOLD,   new java.awt.Color(30, 64, 175));
            com.lowagie.text.Font bodyFont    = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.NORMAL);
            com.lowagie.text.Font bodyBold    = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font tableHead   = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 9,  com.lowagie.text.Font.BOLD,   new java.awt.Color(255, 255, 255));
            com.lowagie.text.Font totalFont   = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 10, com.lowagie.text.Font.BOLD,   new java.awt.Color(30, 64, 175));
            com.lowagie.text.Font smallGray   = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8,  com.lowagie.text.Font.NORMAL, new java.awt.Color(148, 163, 184));

            java.awt.Color headerBg   = new java.awt.Color(30, 64, 175);
            java.awt.Color altRowBg   = new java.awt.Color(241, 245, 249);
            java.awt.Color dividerCol = new java.awt.Color(226, 232, 240);

            // ── Helper: format ISO date strings ──
            java.time.format.DateTimeFormatter humanDate = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy");
            java.util.function.Function<String, String> fmtDate = raw -> {
                if (raw == null || raw.equals("—")) return "—";
                try {
                    if (raw.contains("T")) {
                        return java.time.Instant.parse(raw)
                                .atZone(java.time.ZoneOffset.UTC)
                                .format(humanDate);
                    }
                    return java.time.LocalDate.parse(raw).format(humanDate);
                } catch (Exception ex) { return raw; }
            };

            // ═══════════════════════════════════════════════════════
            // SECTION 1 — Two-column header: brand left | invoice meta right
            // ═══════════════════════════════════════════════════════
            com.lowagie.text.pdf.PdfPTable headerTable = new com.lowagie.text.pdf.PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{55f, 45f});
            headerTable.getDefaultCell().setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            headerTable.getDefaultCell().setPadding(0);

            // Left: brand block
            com.lowagie.text.pdf.PdfPCell brandCell = new com.lowagie.text.pdf.PdfPCell();
            brandCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            brandCell.setPadding(0);
            com.lowagie.text.Paragraph brandPara = new com.lowagie.text.Paragraph("TruckRental", brandFont);
            brandPara.setSpacingAfter(2);
            brandCell.addElement(brandPara);
            brandCell.addElement(new com.lowagie.text.Paragraph("Truck Rental Marketplace", invoiceLabel));
            brandCell.addElement(new com.lowagie.text.Paragraph("support@truckhire.com", invoiceLabel));
            brandCell.addElement(new com.lowagie.text.Paragraph("www.truckhire.com", invoiceLabel));
            headerTable.addCell(brandCell);

            // Right: invoice meta
            com.lowagie.text.pdf.PdfPCell metaCell = new com.lowagie.text.pdf.PdfPCell();
            metaCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            metaCell.setPadding(0);
            metaCell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);

            com.lowagie.text.pdf.PdfPTable metaInner = new com.lowagie.text.pdf.PdfPTable(2);
            metaInner.setWidthPercentage(100);
            metaInner.setWidths(new float[]{45f, 55f});
            java.util.function.BiConsumer<String, String> addMeta = (k, v) -> {
                com.lowagie.text.pdf.PdfPCell kc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(k, metaKey));
                kc.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
                kc.setPaddingBottom(3);
                kc.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);
                com.lowagie.text.pdf.PdfPCell vc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(v, metaVal));
                vc.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
                vc.setPaddingBottom(3);
                vc.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);
                metaInner.addCell(kc);
                metaInner.addCell(vc);
            };
            addMeta.accept("INVOICE", nullSafe(detail.getInvoiceNumber()));
            addMeta.accept("BOOKING", nullSafe(detail.getBookingNumber()));
            addMeta.accept("ISSUED", fmtDate.apply(detail.getPaymentDate()));
            addMeta.accept("STATUS", nullSafe(detail.getDisplayPaymentStatus()).toUpperCase());
            metaCell.addElement(metaInner);
            headerTable.addCell(metaCell);
            doc.add(headerTable);

            // Divider
            com.lowagie.text.pdf.PdfPTable divider = new com.lowagie.text.pdf.PdfPTable(1);
            divider.setWidthPercentage(100);
            divider.setSpacingBefore(10);
            divider.setSpacingAfter(12);
            com.lowagie.text.pdf.PdfPCell divCell = new com.lowagie.text.pdf.PdfPCell();
            divCell.setBackgroundColor(dividerCol);
            divCell.setFixedHeight(1f);
            divCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            divider.addCell(divCell);
            doc.add(divider);

            // ═══════════════════════════════════════════════════════
            // SECTION 2 — Billed To (left) | Vehicle Owner (right)
            // ═══════════════════════════════════════════════════════
            com.lowagie.text.pdf.PdfPTable partyTable = new com.lowagie.text.pdf.PdfPTable(2);
            partyTable.setWidthPercentage(100);
            partyTable.setWidths(new float[]{50f, 50f});
            partyTable.setSpacingAfter(12);
            partyTable.getDefaultCell().setBorder(com.lowagie.text.Rectangle.NO_BORDER);

            // Billed To
            com.lowagie.text.pdf.PdfPCell billedCell = new com.lowagie.text.pdf.PdfPCell();
            billedCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            billedCell.setPaddingBottom(4);
            billedCell.addElement(new com.lowagie.text.Paragraph("BILLED TO", sectionHead));
            if (detail.getBooking() != null && detail.getBooking().getRenter() != null) {
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.RenterInfo renter = detail.getBooking().getRenter();
                billedCell.addElement(new com.lowagie.text.Paragraph(nullSafe(renter.getFullname()), bodyBold));
                billedCell.addElement(new com.lowagie.text.Paragraph(nullSafe(renter.getEmail()), bodyFont));
                billedCell.addElement(new com.lowagie.text.Paragraph(nullSafe(renter.getPhone()), bodyFont));
            }
            partyTable.addCell(billedCell);

            // Vehicle Owner
            com.lowagie.text.pdf.PdfPCell ownerCell = new com.lowagie.text.pdf.PdfPCell();
            ownerCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            ownerCell.setPaddingBottom(4);
            ownerCell.addElement(new com.lowagie.text.Paragraph("VEHICLE OWNER", sectionHead));
            if (detail.getBooking() != null && detail.getBooking().getOwner() != null) {
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo owner = detail.getBooking().getOwner();
                ownerCell.addElement(new com.lowagie.text.Paragraph(nullSafe(owner.getFullname()), bodyBold));
                ownerCell.addElement(new com.lowagie.text.Paragraph(nullSafe(owner.getEmail()), bodyFont));
                if (owner.getPhone() != null) {
                    ownerCell.addElement(new com.lowagie.text.Paragraph(owner.getPhone(), bodyFont));
                }
            }
            partyTable.addCell(ownerCell);
            doc.add(partyTable);

            // ═══════════════════════════════════════════════════════
            // SECTION 3 — Rental Details
            // ═══════════════════════════════════════════════════════
            doc.add(new com.lowagie.text.Paragraph("RENTAL DETAILS", sectionHead));
            doc.add(com.lowagie.text.Chunk.NEWLINE);
            if (detail.getBooking() != null) {
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.BookingDetail booking = detail.getBooking();

                com.lowagie.text.pdf.PdfPTable detailsTable = new com.lowagie.text.pdf.PdfPTable(2);
                detailsTable.setWidthPercentage(100);
                detailsTable.setWidths(new float[]{30f, 70f});
                detailsTable.setSpacingAfter(12);

                java.util.function.BiConsumer<String, String> addRow = (label, value) -> {
                    com.lowagie.text.pdf.PdfPCell lc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(label, metaKey));
                    lc.setBorder(com.lowagie.text.Rectangle.BOTTOM);
                    lc.setBorderColor(dividerCol);
                    lc.setPadding(5);
                    com.lowagie.text.pdf.PdfPCell vc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(value, bodyFont));
                    vc.setBorder(com.lowagie.text.Rectangle.BOTTOM);
                    vc.setBorderColor(dividerCol);
                    vc.setPadding(5);
                    detailsTable.addCell(lc);
                    detailsTable.addCell(vc);
                };

                if (booking.getTruck() != null) {
                    addRow.accept("Vehicle", nullSafe(booking.getTruck().getModel())
                            + "  (" + nullSafe(booking.getTruck().getRegistrationNumber()) + ")");
                    if (booking.getTruck().getLocationCity() != null) {
                        addRow.accept("Truck Base", booking.getTruck().getLocationCity());
                    }
                }
                addRow.accept("Rental Period", fmtDate.apply(booking.getStartDate()) + "  →  " + fmtDate.apply(booking.getEndDate()));
                addRow.accept("Pickup Location", nullSafe(booking.getPickupLocation()));
                addRow.accept("Drop-off Location", nullSafe(booking.getDropoffLocation()));
                doc.add(detailsTable);
            }

            // ═══════════════════════════════════════════════════════
            // SECTION 4 — Cost Breakdown (line-item table)
            // ═══════════════════════════════════════════════════════
            doc.add(new com.lowagie.text.Paragraph("COST BREAKDOWN", sectionHead));
            doc.add(com.lowagie.text.Chunk.NEWLINE);

            com.lowagie.text.pdf.PdfPTable costTable = new com.lowagie.text.pdf.PdfPTable(4);
            costTable.setWidthPercentage(100);
            costTable.setWidths(new float[]{40f, 20f, 20f, 20f});
            costTable.setSpacingAfter(6);

            // Table header row
            String[] colHeaders = {"Description", "Rate", "Qty / Unit", "Amount"};
            for (String col : colHeaders) {
                com.lowagie.text.pdf.PdfPCell hc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(col, tableHead));
                hc.setBackgroundColor(headerBg);
                hc.setPadding(6);
                hc.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
                hc.setHorizontalAlignment(col.equals("Description") ? com.lowagie.text.Element.ALIGN_LEFT : com.lowagie.text.Element.ALIGN_RIGHT);
                costTable.addCell(hc);
            }

            boolean altRow = false;
            java.util.function.Function<Boolean, java.awt.Color> rowBg = alt -> alt ? altRowBg : java.awt.Color.WHITE;

            java.util.function.Consumer<String[]> addLineItem = cols -> {
                // cols: [description, rate, qty, amount]
                boolean isAlt = altRow; // capture — effectively final workaround via array
                // We use a shared mutable boolean via the table's row count instead
                for (int ci = 0; ci < cols.length; ci++) {
                    com.lowagie.text.pdf.PdfPCell lc = new com.lowagie.text.pdf.PdfPCell(
                            new com.lowagie.text.Phrase(cols[ci], ci == 0 ? bodyFont : bodyFont));
                    lc.setBorder(com.lowagie.text.Rectangle.BOTTOM);
                    lc.setBorderColor(dividerCol);
                    lc.setPadding(6);
                    lc.setHorizontalAlignment(ci == 0 ? com.lowagie.text.Element.ALIGN_LEFT : com.lowagie.text.Element.ALIGN_RIGHT);
                    costTable.addCell(lc);
                }
            };

            if (detail.getCombinedSummary() != null) {
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.CombinedSummary s = detail.getCombinedSummary();

                // Day-rate line: compute number of days from booking dates
                int rentalDays = 1;
                if (detail.getBooking() != null) {
                    try {
                        java.time.LocalDate sd = java.time.LocalDate.parse(detail.getBooking().getStartDate());
                        java.time.LocalDate ed = java.time.LocalDate.parse(detail.getBooking().getEndDate());
                        rentalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(sd, ed);
                        if (rentalDays < 1) rentalDays = 1;
                    } catch (Exception ignored) {}
                }
                BigDecimal pricePerDay = (detail.getBooking() != null && detail.getBooking().getTruck() != null)
                        ? detail.getBooking().getTruck().getPricePerDay() : null;
                String rateStr = pricePerDay != null ? "$" + pricePerDay : "—";
                addLineItem.accept(new String[]{"Base Rental (Day Rate)", rateStr, rentalDays + " day" + (rentalDays == 1 ? "" : "s"), "$" + s.getDayAmount()});

                if (s.getMileageAmount() != null && s.getMileageAmount().compareTo(BigDecimal.ZERO) > 0) {
                    String cpm = detail.getBooking() != null && detail.getBooking().getTruck() != null ? "—" : "—";
                    // mileage rate from detail if available
                    addLineItem.accept(new String[]{"Mileage Charge", "per mile", "—", "$" + s.getMileageAmount()});
                }
                if (s.getInsuranceCost() != null && s.getInsuranceCost().compareTo(BigDecimal.ZERO) > 0) {
                    addLineItem.accept(new String[]{"Insurance", "—", "—", "$" + s.getInsuranceCost()});
                }
                if (s.getAdditionalServicesCost() != null && s.getAdditionalServicesCost().compareTo(BigDecimal.ZERO) > 0) {
                    addLineItem.accept(new String[]{"Additional Services", "—", "—", "$" + s.getAdditionalServicesCost()});
                }
            } else {
                addLineItem.accept(new String[]{"Rental Charge", "—", "—", "$" + nullSafe(detail.getTotal())});
            }
            doc.add(costTable);

            // Total row
            com.lowagie.text.pdf.PdfPTable totalTable = new com.lowagie.text.pdf.PdfPTable(2);
            totalTable.setWidthPercentage(50);
            totalTable.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);
            totalTable.setWidths(new float[]{50f, 50f});
            totalTable.setSpacingAfter(16);

            com.lowagie.text.pdf.PdfPCell totalLabelCell = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase("TOTAL PAID", totalFont));
            totalLabelCell.setBorder(com.lowagie.text.Rectangle.TOP);
            totalLabelCell.setBorderColor(headerBg);
            totalLabelCell.setPadding(6);
            totalLabelCell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_LEFT);

            String totalAmt = detail.getCombinedSummary() != null
                    ? "$" + detail.getCombinedSummary().getTotalPaid()
                    : "$" + nullSafe(detail.getTotal());
            com.lowagie.text.pdf.PdfPCell totalAmtCell = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(totalAmt, totalFont));
            totalAmtCell.setBorder(com.lowagie.text.Rectangle.TOP);
            totalAmtCell.setBorderColor(headerBg);
            totalAmtCell.setPadding(6);
            totalAmtCell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);

            totalTable.addCell(totalLabelCell);
            totalTable.addCell(totalAmtCell);
            doc.add(totalTable);

            // ═══════════════════════════════════════════════════════
            // SECTION 5 — Payment & Settlement Reference
            // ═══════════════════════════════════════════════════════
            doc.add(new com.lowagie.text.Paragraph("PAYMENT REFERENCE", sectionHead));
            doc.add(com.lowagie.text.Chunk.NEWLINE);

            com.lowagie.text.pdf.PdfPTable refTable = new com.lowagie.text.pdf.PdfPTable(2);
            refTable.setWidthPercentage(100);
            refTable.setWidths(new float[]{30f, 70f});
            refTable.setSpacingAfter(12);

            java.util.function.BiConsumer<String, String> addRefRow = (label, value) -> {
                com.lowagie.text.pdf.PdfPCell lc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(label, metaKey));
                lc.setBorder(com.lowagie.text.Rectangle.BOTTOM);
                lc.setBorderColor(dividerCol);
                lc.setPadding(5);
                com.lowagie.text.pdf.PdfPCell vc = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(value, bodyFont));
                vc.setBorder(com.lowagie.text.Rectangle.BOTTOM);
                vc.setBorderColor(dividerCol);
                vc.setPadding(5);
                refTable.addCell(lc);
                refTable.addCell(vc);
            };

            addRefRow.accept("Transaction ID", nullSafe(detail.getTransactionId()));
            addRefRow.accept("Payment Gateway", nullSafe(detail.getGateway()));
            if (detail.getCardLast4() != null) {
                addRefRow.accept("Card", "**** **** **** " + detail.getCardLast4());
            }
            addRefRow.accept("Settlement Status", nullSafe(detail.getDisplaySettlementStatus()));
            if (detail.getBooking() != null && detail.getBooking().getOwner() != null) {
                com.truckhire.modules.payment.dto.AdminInvoiceDetailResponse.OwnerInfo owner = detail.getBooking().getOwner();
                if (owner.getSettlementId() != null) {
                    addRefRow.accept("Settlement ID", owner.getSettlementId());
                    addRefRow.accept("Settlement Date", fmtDate.apply(owner.getSettlementDate()));
                }
            }
            doc.add(refTable);

            // ═══════════════════════════════════════════════════════
            // SECTION 6 — Footer
            // ═══════════════════════════════════════════════════════
            com.lowagie.text.pdf.PdfPTable footerDivider = new com.lowagie.text.pdf.PdfPTable(1);
            footerDivider.setWidthPercentage(100);
            footerDivider.setSpacingBefore(8);
            footerDivider.setSpacingAfter(8);
            com.lowagie.text.pdf.PdfPCell fd = new com.lowagie.text.pdf.PdfPCell();
            fd.setBackgroundColor(dividerCol);
            fd.setFixedHeight(1f);
            fd.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            footerDivider.addCell(fd);
            doc.add(footerDivider);

            com.lowagie.text.Paragraph footer1 = new com.lowagie.text.Paragraph(
                    "Thank you for choosing TruckRental. For billing inquiries, contact support@truckhire.com", smallGray);
            footer1.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            doc.add(footer1);

            com.lowagie.text.Paragraph footer2 = new com.lowagie.text.Paragraph(
                    "This is a system-generated invoice. No signature required.  |  www.truckhire.com", smallGray);
            footer2.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            doc.add(footer2);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new com.truckhire.common.exception.BusinessException("PDF_GENERATION_FAILED",
                    "Failed to generate invoice PDF: " + e.getMessage());
        }
    }

    /**
     * Generates a PDF invoice for a CHARGE transaction belonging to the requesting user.
     * RENTER may download invoices for bookings they made.
     * OWNER may download invoices for bookings on their trucks.
     * Used by GET /payments/{id}/download.
     */
    @Transactional(readOnly = true)
    public byte[] generateInvoicePdfForUser(UUID transactionId, UUID userId) {
        PaymentTransaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new com.truckhire.common.exception.ResourceNotFoundException(
                        "PaymentTransaction", "id", transactionId));

        com.truckhire.modules.booking.entity.Booking booking = txn.getBooking();
        boolean isRenter = booking.getRenter().getId().equals(userId);
        boolean isOwner  = booking.getOwner().getId().equals(userId);

        if (!isRenter && !isOwner) {
            throw new com.truckhire.common.exception.BusinessException(
                    "ACCESS_DENIED", "You do not have access to this invoice.");
        }

        return generateInvoicePdf(transactionId);
    }

    private String nullSafe(Object val) {
        return val != null ? val.toString() : "—";
    }

}
