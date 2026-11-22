package com.example.payment_service.service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentRecoveryTransactionService recoveryTransactionService;

    @InjectMocks
    private PaymentRecoveryService paymentRecoveryService;

    private Payment payment1;
    private Payment payment2;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                paymentRecoveryService,
                "staleThresholdMinutes",
                5L
        );

        payment1 = new Payment();
        payment1.setId(101L);
        payment1.setStatus(PaymentStatus.PROCESSING);

        payment2 = new Payment();
        payment2.setId(102L);
        payment2.setStatus(PaymentStatus.UNKNOWN);
    }

    @Test
    void shouldRecoverStalePaymentsAndReturnRecoveredCount() {

        when(paymentRepository.findAllByStatusInAndUpdatedAtBefore(
                anyList(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(payment1, payment2));

        when(recoveryTransactionService.recoverPayment(
                eq(101L),
                any(OffsetDateTime.class)
        )).thenReturn(true);

        when(recoveryTransactionService.recoverPayment(
                eq(102L),
                any(OffsetDateTime.class)
        )).thenReturn(false);

        int recoveredCount =
                paymentRecoveryService.recoverStalePayments();

        assertEquals(1, recoveredCount);

        verify(recoveryTransactionService, times(1))
                .recoverPayment(eq(101L), any(OffsetDateTime.class));

        verify(recoveryTransactionService, times(1))
                .recoverPayment(eq(102L), any(OffsetDateTime.class));
    }

    @Test
    void shouldReturnZeroWhenNoStalePaymentsExist() {

        when(paymentRepository.findAllByStatusInAndUpdatedAtBefore(
                anyList(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of());

        int recoveredCount =
                paymentRecoveryService.recoverStalePayments();

        assertEquals(0, recoveredCount);

        verifyNoInteractions(recoveryTransactionService);
    }
}