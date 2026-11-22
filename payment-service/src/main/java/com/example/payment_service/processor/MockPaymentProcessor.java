package com.example.payment_service.processor;

import com.example.payment_service.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessingResult processPayment(Payment payment) {

        String paymentMethod = payment.getPaymentMethod();

        // Simulate a definite payment failure.
        if ("FAIL".equalsIgnoreCase(paymentMethod)) {
            return PaymentProcessingResult.FAILED;
        }

        // Simulate an uncertain payment outcome.
        if ("UNKNOWN".equalsIgnoreCase(paymentMethod)) {
            return PaymentProcessingResult.UNKNOWN;
        }

        // Simulate a successful payment.
        return PaymentProcessingResult.SUCCESS;
    }
}