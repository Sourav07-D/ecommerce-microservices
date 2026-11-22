package com.example.payment_service.processor;

import com.example.payment_service.entity.Payment;

public interface PaymentProcessor {

    PaymentProcessingResult processPayment(Payment payment);
}