package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.repository.PaymentRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaymentRecoveryService {

    private final PaymentRepository paymentRepository;
    private final PaymentRecoveryTransactionService recoveryTransactionService;

    @Value("${payment.recovery.stale-threshold-minutes:5}")
    private long staleThresholdMinutes;

    public PaymentRecoveryService(
            PaymentRepository paymentRepository,
            PaymentRecoveryTransactionService recoveryTransactionService) {

        this.paymentRepository = paymentRepository;
        this.recoveryTransactionService = recoveryTransactionService;
    }

    public int recoverStalePayments() {

        OffsetDateTime cutoffTime =
                OffsetDateTime.now().minusMinutes(staleThresholdMinutes);

        List<Payment> stalePayments =
                paymentRepository.findAllByStatusInAndUpdatedAtBefore(
                        List.of(
                                PaymentStatus.PROCESSING,
                                PaymentStatus.UNKNOWN
                        ),
                        cutoffTime
                );

        int recoveredCount = 0;

        for (Payment payment : stalePayments) {

            boolean recovered =
                    recoveryTransactionService.recoverPayment(
                            payment.getId(),
                            cutoffTime
                    );

            if (recovered) {
                recoveredCount++;
            }
        }

        return recoveredCount;
    }
}