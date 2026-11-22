package com.example.payment_service;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.processor.PaymentProcessingResult;
import com.example.payment_service.processor.PaymentStatusLookup;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentRecoveryTransactionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class PaymentRecoveryDatabaseIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentRecoveryTransactionService recoveryTransactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentStatusLookup paymentStatusLookup;

    private final List<Long> createdPaymentIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long paymentId : createdPaymentIds) {
            paymentRepository.deleteById(paymentId);
        }

        createdPaymentIds.clear();
        reset(paymentStatusLookup);
    }

    @Test
    void shouldRecoverStalePaymentAsSuccessfulInDatabase() {

        Payment payment = createStalePayment("recovery-success");

        when(paymentStatusLookup.lookupPaymentStatus(any(Payment.class)))
                .thenReturn(PaymentProcessingResult.SUCCESS);

        boolean recovered = recoveryTransactionService.recoverPayment(
                payment.getId(),
                OffsetDateTime.now().minusMinutes(5)
        );

        assertTrue(recovered);

        Payment updatedPayment = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

        assertEquals(PaymentStatus.SUCCESS, updatedPayment.getStatus());

        verify(paymentStatusLookup, times(1))
                .lookupPaymentStatus(any(Payment.class));
    }

    @Test
    void shouldRecoverStalePaymentAsFailedInDatabase() {

        Payment payment = createStalePayment("recovery-failed");

        when(paymentStatusLookup.lookupPaymentStatus(any(Payment.class)))
                .thenReturn(PaymentProcessingResult.FAILED);

        boolean recovered = recoveryTransactionService.recoverPayment(
                payment.getId(),
                OffsetDateTime.now().minusMinutes(5)
        );

        assertTrue(recovered);

        Payment updatedPayment = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

        assertEquals(PaymentStatus.FAILED, updatedPayment.getStatus());

        verify(paymentStatusLookup, times(1))
                .lookupPaymentStatus(any(Payment.class));
    }

    @Test
    void shouldKeepPaymentUnknownWhenProviderCannotConfirmOutcome() {

        Payment payment = createStalePayment("recovery-unknown");

        when(paymentStatusLookup.lookupPaymentStatus(any(Payment.class)))
                .thenReturn(PaymentProcessingResult.UNKNOWN);

        boolean recovered = recoveryTransactionService.recoverPayment(
                payment.getId(),
                OffsetDateTime.now().minusMinutes(5)
        );

        assertFalse(recovered);

        Payment updatedPayment = paymentRepository
                .findById(payment.getId())
                .orElseThrow();

        assertEquals(PaymentStatus.UNKNOWN, updatedPayment.getStatus());

        verify(paymentStatusLookup, times(1))
                .lookupPaymentStatus(any(Payment.class));
    }

    @Test
    void shouldPreventConcurrentRecoveryOfSamePayment() throws Exception {

        Payment payment = createStalePayment("recovery-concurrent");

        CountDownLatch firstLookupStarted = new CountDownLatch(1);
        CountDownLatch allowFirstLookupToFinish = new CountDownLatch(1);
        CountDownLatch secondWorkerStarted = new CountDownLatch(1);

        when(paymentStatusLookup.lookupPaymentStatus(any(Payment.class)))
                .thenAnswer(invocation -> {
                    firstLookupStarted.countDown();

                    if (!allowFirstLookupToFinish.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to release first recovery"
                        );
                    }

                    return PaymentProcessingResult.SUCCESS;
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            OffsetDateTime cutoffTime = OffsetDateTime.now().minusMinutes(5);

            // Worker 1 acquires the database lock and pauses during lookup.
            Future<Boolean> firstWorker = executor.submit(
                    () -> recoveryTransactionService.recoverPayment(
                            payment.getId(),
                            cutoffTime
                    )
            );

            assertTrue(
                    firstLookupStarted.await(5, TimeUnit.SECONDS),
                    "First worker did not reach provider lookup"
            );

            // Worker 2 attempts recovery while worker 1 holds the row lock.
            Future<Boolean> secondWorker = executor.submit(() -> {
                secondWorkerStarted.countDown();

                return recoveryTransactionService.recoverPayment(
                        payment.getId(),
                        cutoffTime
                );
            });

            assertTrue(
                    secondWorkerStarted.await(5, TimeUnit.SECONDS),
                    "Second worker did not start"
            );

            // The second worker should be waiting for the database row lock.
            Thread.sleep(300);

            assertFalse(
                    secondWorker.isDone(),
                    "Second worker should wait while the first worker holds the lock"
            );

            // Allow worker 1 to finish and commit SUCCESS.
            allowFirstLookupToFinish.countDown();

            assertTrue(
                    firstWorker.get(5, TimeUnit.SECONDS),
                    "First worker should successfully recover the payment"
            );

            assertFalse(
                    secondWorker.get(5, TimeUnit.SECONDS),
                    "Second worker must skip the already recovered payment"
            );

            Payment updatedPayment = paymentRepository
                    .findById(payment.getId())
                    .orElseThrow();

            assertEquals(PaymentStatus.SUCCESS, updatedPayment.getStatus());

            // Only the first worker should have queried the provider.
            verify(paymentStatusLookup, times(1))
                    .lookupPaymentStatus(any(Payment.class));

        } finally {
            allowFirstLookupToFinish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Payment createStalePayment(String idempotencyKey) {

        Payment payment = new Payment();

        payment.setOrderId(900L);
        payment.setAmount(new BigDecimal("999.00"));
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.PROCESSING);
        payment.setPaymentMethod("UPI");
        payment.setIdempotencyKey(idempotencyKey);

        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        createdPaymentIds.add(savedPayment.getId());

        jdbcTemplate.update(
                """
                UPDATE payments
                SET updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                WHERE id = ?
                """,
                savedPayment.getId()
        );

        return paymentRepository.findById(savedPayment.getId())
                .orElseThrow();
    }
}