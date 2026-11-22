package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.processor.PaymentProcessingResult;
import com.example.payment_service.processor.PaymentStatusLookup;
import com.example.payment_service.repository.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaymentRecoveryTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusLookup paymentStatusLookup;

    public PaymentRecoveryTransactionService(
            PaymentRepository paymentRepository,
            PaymentStatusLookup paymentStatusLookup) {

        this.paymentRepository = paymentRepository;
        this.paymentStatusLookup = paymentStatusLookup;
    }

    @Transactional
    public boolean recoverPayment(Long paymentId, OffsetDateTime cutoffTime) {

        Payment payment = paymentRepository
                .findByIdForUpdate(paymentId)
                .orElse(null);

        if (payment == null) {
            return false;
        }

        // Recheck eligibility while holding the database row lock.
        if (!List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN)
                .contains(payment.getStatus())) {
            return false;
        }

        if (!payment.getUpdatedAt().isBefore(cutoffTime)) {
            return false;
        }

        // Only query the provider. Never initiate payment processing again.
        PaymentProcessingResult result =
                paymentStatusLookup.lookupPaymentStatus(payment);

        if (result == null || result == PaymentProcessingResult.UNKNOWN) {
            payment.setStatus(PaymentStatus.UNKNOWN);
            paymentRepository.save(payment);
            return false;
        }

        if (result == PaymentProcessingResult.SUCCESS) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            return true;
        }

        if (result == PaymentProcessingResult.FAILED) {
            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            return true;
        }

        return false;
    }
}