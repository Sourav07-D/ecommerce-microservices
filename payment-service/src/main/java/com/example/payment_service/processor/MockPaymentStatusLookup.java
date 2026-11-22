package com.example.payment_service.processor;

import com.example.payment_service.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentStatusLookup implements PaymentStatusLookup {

    @Override
    public PaymentProcessingResult lookupPaymentStatus(Payment payment) {

        String paymentMethod = payment.getPaymentMethod();

        if ("LOOKUP_SUCCESS".equalsIgnoreCase(paymentMethod)) {
            return PaymentProcessingResult.SUCCESS;
        }

        if ("LOOKUP_FAILED".equalsIgnoreCase(paymentMethod)) {
            return PaymentProcessingResult.FAILED;
        }

        // Unknown or unrecognized outcomes must remain uncertain.
        return PaymentProcessingResult.UNKNOWN;
    }
}