package com.truckhire.modules.payment.repository;

import com.truckhire.modules.payment.entity.PaymentTransaction;
import com.truckhire.modules.payment.enums.PaymentStatus;
import com.truckhire.modules.payment.enums.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    Optional<PaymentTransaction> findByBookingIdAndTypeAndStatus(
            UUID bookingId, PaymentType type, PaymentStatus status);

    Optional<PaymentTransaction> findFirstByBookingIdAndTypeOrderByCreatedAtDesc(
            UUID bookingId, PaymentType type);

    Optional<PaymentTransaction> findFirstByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    boolean existsByBookingIdAndTypeAndStatusIn(UUID bookingId, PaymentType type, java.util.List<PaymentStatus> statuses);
}
