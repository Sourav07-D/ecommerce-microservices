package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentRequestDTO;
import com.example.payment_service.dto.PaymentResponseDTO;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.IdempotencyKeyConflictException;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.processor.PaymentProcessor;
import com.example.payment_service.repository.PaymentRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentProcessor paymentProcessor) {

        this.paymentRepository = paymentRepository;
        this.paymentProcessor = paymentProcessor;
    }

    // Create and process a payment with concurrency-safe idempotency.
    public PaymentResponseDTO createPayment(PaymentRequestDTO request) {

        var existingPayment =
                paymentRepository.findByIdempotencyKey(
                        request.getIdempotencyKey()
                );

        if (existingPayment.isPresent()) {

            validateIdempotencyRequest(existingPayment.get(), request);

            return mapToResponse(existingPayment.get());
        }

        // Reserve the idempotency key before processing the payment.
        Payment payment = new Payment();

        payment.setOrderId(request.getOrderId());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setIdempotencyKey(request.getIdempotencyKey());
        payment.setStatus(PaymentStatus.PENDING);

        Payment savedPayment;

        try {

            // Flush ensures the unique-key constraint is checked here.
            // No payment processing happens before this reservation succeeds.
            savedPayment = paymentRepository.saveAndFlush(payment);

        } catch (DataIntegrityViolationException exception) {

            // Another concurrent request may have reserved this key first.
            Payment existingPaymentAfterConflict =
                    paymentRepository
                            .findByIdempotencyKey(request.getIdempotencyKey())
                            .orElseThrow(() -> exception);

            validateIdempotencyRequest(existingPaymentAfterConflict, request);

            return mapToResponse(existingPaymentAfterConflict);
        }

        // Process only after successfully reserving the idempotency key.
        boolean successful = paymentProcessor.processPayment(savedPayment);

        if (successful) {

            savedPayment.setStatus(PaymentStatus.SUCCESS);

            savedPayment.setTransactionReference(
                    "TXN-" + UUID.randomUUID()
            );

        } else {

            savedPayment.setStatus(PaymentStatus.FAILED);
        }

        Payment updatedPayment = paymentRepository.save(savedPayment);

        return mapToResponse(updatedPayment);
    }

    // Validate that an existing key is reused with identical details.
    private void validateIdempotencyRequest(
            Payment existingPayment,
            PaymentRequestDTO request) {

        boolean sameOrderId =
                existingPayment.getOrderId().equals(request.getOrderId());

        boolean sameAmount =
                existingPayment.getAmount().compareTo(request.getAmount()) == 0;

        boolean sameCurrency =
                existingPayment.getCurrency().equalsIgnoreCase(
                        request.getCurrency()
                );

        boolean samePaymentMethod =
                existingPayment.getPaymentMethod().equalsIgnoreCase(
                        request.getPaymentMethod()
                );

        if (!sameOrderId || !sameAmount || !sameCurrency || !samePaymentMethod) {

            throw new IdempotencyKeyConflictException(
                    "Idempotency key has already been used with different payment details"
            );
        }
    }

    // Retrieve payment by ID.
    @Transactional(readOnly = true)
    public PaymentResponseDTO getPaymentById(Long paymentId) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() ->
                        new PaymentNotFoundException(paymentId)
                );

        return mapToResponse(payment);
    }

    // Retrieve all payments associated with an order.
    @Transactional(readOnly = true)
    public List<PaymentResponseDTO> getPaymentsByOrderId(Long orderId) {

        List<Payment> payments =
                paymentRepository.findAllByOrderId(orderId);

        return payments.stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Convert entity to response DTO.
    private PaymentResponseDTO mapToResponse(Payment payment) {

        PaymentResponseDTO response = new PaymentResponseDTO();

        response.setId(payment.getId());
        response.setOrderId(payment.getOrderId());
        response.setAmount(payment.getAmount());
        response.setCurrency(payment.getCurrency());
        response.setStatus(payment.getStatus());
        response.setPaymentMethod(payment.getPaymentMethod());
        response.setTransactionReference(payment.getTransactionReference());
        response.setCreatedAt(payment.getCreatedAt());

        return response;
    }
}