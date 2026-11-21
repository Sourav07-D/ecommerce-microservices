package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentRequestDTO;
import com.example.payment_service.dto.PaymentResponseDTO;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.IdempotencyKeyConflictException;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.processor.PaymentProcessor;
import com.example.payment_service.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentProcessor paymentProcessor;

    @InjectMocks
    private PaymentService paymentService;

    private PaymentRequestDTO request;

    @BeforeEach
    void setUp() {

        request = new PaymentRequestDTO();

        request.setOrderId(39L);
        request.setAmount(new BigDecimal("2500.00"));
        request.setCurrency("INR");
        request.setPaymentMethod("UPI");
        request.setIdempotencyKey("test-payment-key-001");
    }

    @Test
    void createPayment_shouldReturnSuccessfulPayment() {

        when(paymentRepository.findByIdempotencyKey(
                request.getIdempotencyKey()
        )).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(1L);
                    return payment;
                });

        when(paymentProcessor.processPayment(any(Payment.class)))
                .thenReturn(true);

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponseDTO response =
                paymentService.createPayment(request);

        assertEquals(1L, response.getId());
        assertEquals(39L, response.getOrderId());
        assertEquals(0, new BigDecimal("2500.00")
                .compareTo(response.getAmount()));
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertNotNull(response.getTransactionReference());

        verify(paymentRepository).saveAndFlush(any(Payment.class));
        verify(paymentProcessor).processPayment(any(Payment.class));
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void createPayment_shouldReturnFailedPayment() {

        when(paymentRepository.findByIdempotencyKey(
                request.getIdempotencyKey()
        )).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment payment = invocation.getArgument(0);
                    payment.setId(2L);
                    return payment;
                });

        when(paymentProcessor.processPayment(any(Payment.class)))
                .thenReturn(false);

        when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponseDTO response =
                paymentService.createPayment(request);

        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNull(response.getTransactionReference());

        verify(paymentProcessor).processPayment(any(Payment.class));
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void createPayment_shouldReturnExistingPaymentForSameIdempotencyKey() {

        Payment existingPayment = createExistingPayment();

        when(paymentRepository.findByIdempotencyKey(
                request.getIdempotencyKey()
        )).thenReturn(Optional.of(existingPayment));

        PaymentResponseDTO response =
                paymentService.createPayment(request);

        assertEquals(10L, response.getId());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());

        verify(paymentProcessor, never())
                .processPayment(any(Payment.class));

        verify(paymentRepository, never())
                .saveAndFlush(any(Payment.class));
    }

    @Test
    void createPayment_shouldThrowConflictForDifferentAmount() {

        Payment existingPayment = createExistingPayment();

        request.setAmount(new BigDecimal("3000.00"));

        assertIdempotencyConflict(existingPayment);
    }

    @Test
    void createPayment_shouldThrowConflictForDifferentOrderId() {

        Payment existingPayment = createExistingPayment();

        request.setOrderId(100L);

        assertIdempotencyConflict(existingPayment);
    }

    @Test
    void createPayment_shouldThrowConflictForDifferentCurrency() {

        Payment existingPayment = createExistingPayment();

        request.setCurrency("USD");

        assertIdempotencyConflict(existingPayment);
    }

    @Test
    void createPayment_shouldThrowConflictForDifferentPaymentMethod() {

        Payment existingPayment = createExistingPayment();

        request.setPaymentMethod("CARD");

        assertIdempotencyConflict(existingPayment);
    }

    @Test
    void createPayment_shouldReturnExistingPaymentWhenConcurrentRequestWins() {

        Payment existingPayment = createExistingPayment();

        when(paymentRepository.findByIdempotencyKey(
                request.getIdempotencyKey()
        )).thenReturn(
                Optional.empty(),
                Optional.of(existingPayment)
        );

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Duplicate idempotency key"
                ));

        PaymentResponseDTO response =
                paymentService.createPayment(request);

        assertEquals(10L, response.getId());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());

        verify(paymentRepository).saveAndFlush(any(Payment.class));

        verify(paymentProcessor, never())
                .processPayment(any(Payment.class));

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void getPaymentById_shouldReturnExistingPayment() {

        Payment existingPayment = createExistingPayment();

        when(paymentRepository.findById(10L))
                .thenReturn(Optional.of(existingPayment));

        PaymentResponseDTO response =
                paymentService.getPaymentById(10L);

        assertEquals(10L, response.getId());
        assertEquals(39L, response.getOrderId());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
    }

    @Test
    void getPaymentById_shouldThrowExceptionWhenPaymentDoesNotExist() {

        when(paymentRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                PaymentNotFoundException.class,
                () -> paymentService.getPaymentById(999L)
        );
    }

    @Test
    void getPaymentsByOrderId_shouldReturnAllPaymentsForOrder() {

        Payment firstPayment = createExistingPayment();

        Payment secondPayment = createExistingPayment();
        secondPayment.setId(11L);
        secondPayment.setAmount(new BigDecimal("1000.00"));
        secondPayment.setIdempotencyKey("test-payment-key-002");
        secondPayment.setTransactionReference("TXN-TEST-002");

        when(paymentRepository.findAllByOrderId(39L))
                .thenReturn(List.of(firstPayment, secondPayment));

        List<PaymentResponseDTO> responses =
                paymentService.getPaymentsByOrderId(39L);

        assertEquals(2, responses.size());

        assertEquals(10L, responses.get(0).getId());
        assertEquals(11L, responses.get(1).getId());

        verify(paymentRepository).findAllByOrderId(39L);
    }

    @Test
    void getPaymentsByOrderId_shouldReturnEmptyListWhenNoPaymentsExist() {

        when(paymentRepository.findAllByOrderId(999L))
                .thenReturn(List.of());

        List<PaymentResponseDTO> responses =
                paymentService.getPaymentsByOrderId(999L);

        assertNotNull(responses);
        assertTrue(responses.isEmpty());
    }

    private void assertIdempotencyConflict(Payment existingPayment) {

        when(paymentRepository.findByIdempotencyKey(
                request.getIdempotencyKey()
        )).thenReturn(Optional.of(existingPayment));

        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> paymentService.createPayment(request)
        );

        verify(paymentProcessor, never())
                .processPayment(any(Payment.class));

        verify(paymentRepository, never())
                .saveAndFlush(any(Payment.class));

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    private Payment createExistingPayment() {

        Payment payment = new Payment();

        payment.setId(10L);
        payment.setOrderId(39L);
        payment.setAmount(new BigDecimal("2500.00"));
        payment.setCurrency("INR");
        payment.setPaymentMethod("UPI");
        payment.setIdempotencyKey("test-payment-key-001");
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionReference("TXN-TEST-001");

        return payment;
    }
}