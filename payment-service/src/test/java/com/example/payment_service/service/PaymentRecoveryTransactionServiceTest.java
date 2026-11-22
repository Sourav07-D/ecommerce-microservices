package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.processor.PaymentProcessingResult;
import com.example.payment_service.processor.PaymentStatusLookup;
import com.example.payment_service.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentStatusLookup paymentStatusLookup;

    @InjectMocks
    private PaymentRecoveryTransactionService recoveryTransactionService;

    private Payment payment;
    private OffsetDateTime cutoffTime;

    @BeforeEach
    void setUp() {
        cutoffTime = OffsetDateTime.now().minusMinutes(5);

        payment = new Payment();
        payment.setId(100L);
        payment.setOrderId(50L);
        payment.setAmount(new BigDecimal("999.00"));
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setPaymentMethod("UPI");
        payment.setIdempotencyKey("recovery-test-100");
        payment.setUpdatedAt(OffsetDateTime.now().minusMinutes(10));
    }

    @Test
    void shouldRecoverPaymentAsSuccessful() {
        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.of(payment));

        when(paymentStatusLookup.lookupPaymentStatus(payment))
                .thenReturn(PaymentProcessingResult.SUCCESS);

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertTrue(recovered);
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());

        verify(paymentRepository).save(payment);
        verify(paymentStatusLookup).lookupPaymentStatus(payment);
    }

    @Test
    void shouldRecoverPaymentAsFailed() {
        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.of(payment));

        when(paymentStatusLookup.lookupPaymentStatus(payment))
                .thenReturn(PaymentProcessingResult.FAILED);

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertTrue(recovered);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());

        verify(paymentRepository).save(payment);
    }

    @Test
    void shouldKeepPaymentUnknownWhenProviderCannotConfirmOutcome() {
        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.of(payment));

        when(paymentStatusLookup.lookupPaymentStatus(payment))
                .thenReturn(PaymentProcessingResult.UNKNOWN);

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertFalse(recovered);
        assertEquals(PaymentStatus.UNKNOWN, payment.getStatus());

        verify(paymentRepository).save(payment);
    }

    @Test
    void shouldNotRecoverPaymentThatIsNotStale() {
        payment.setUpdatedAt(OffsetDateTime.now().minusMinutes(2));

        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.of(payment));

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertFalse(recovered);

        verify(paymentStatusLookup, never()).lookupPaymentStatus(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldNotRecoverPaymentThatIsAlreadySuccessful() {
        payment.setStatus(PaymentStatus.SUCCESS);

        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.of(payment));

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertFalse(recovered);

        verify(paymentStatusLookup, never()).lookupPaymentStatus(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void shouldReturnFalseWhenPaymentDoesNotExist() {
        when(paymentRepository.findByIdForUpdate(100L))
                .thenReturn(Optional.empty());

        boolean recovered =
                recoveryTransactionService.recoverPayment(100L, cutoffTime);

        assertFalse(recovered);

        verify(paymentStatusLookup, never()).lookupPaymentStatus(any());
        verify(paymentRepository, never()).save(any());
    }
}