package com.example.payment_service.processor;

import com.example.payment_service.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentProcessor implements PaymentProcessor {

    @Override
    public boolean processPayment(Payment payment) {

        // Simulate a failed transaction when payment method is FAIL
        if ("FAIL".equalsIgnoreCase(payment.getPaymentMethod())) {
            return false;
        }

        // Simulate successful payment processing
        return true;
    }
}