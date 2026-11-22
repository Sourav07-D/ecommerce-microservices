package com.example.payment_service;

import com.example.payment_service.dto.PaymentRequestDTO;
import com.example.payment_service.dto.PaymentResponseDTO;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.IdempotencyKeyConflictException;
import com.example.payment_service.processor.PaymentProcessingResult;
import com.example.payment_service.processor.PaymentProcessor;
import com.example.payment_service.service.PaymentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceDatabaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentService paymentService;

    @MockitoBean
    private PaymentProcessor paymentProcessor;

    @BeforeEach
    void configurePaymentProcessor() {
        when(paymentProcessor.processPayment(any(Payment.class)))
                .thenReturn(PaymentProcessingResult.SUCCESS);
    }

    @Test
    void shouldConnectToTestDatabaseAndFindPaymentsTable() {

        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'payments'
                """,
                Integer.class
        );

        assertEquals("payment_test_db", databaseName);
        assertEquals(1, tableCount);
    }

    @Test
    void shouldPersistSuccessfulPaymentInDatabase() {

        String idempotencyKey = generateIdempotencyKey();

        PaymentRequestDTO request = createPaymentRequest(
                900001L,
                new BigDecimal("125.50"),
                "UPI",
                idempotencyKey
        );

        PaymentResponseDTO response = paymentService.createPayment(request);

        assertNotNull(response.getId());
        assertEquals(900001L, response.getOrderId());
        assertEquals(new BigDecimal("125.50"), response.getAmount());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertNotNull(response.getTransactionReference());

        Integer paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE idempotency_key = ?
                """,
                Integer.class,
                idempotencyKey
        );

        assertEquals(1, paymentCount);
    }

    @Test
    void shouldReturnExistingPaymentForDuplicateIdempotencyKey() {

        String idempotencyKey = generateIdempotencyKey();

        PaymentRequestDTO request = createPaymentRequest(
                900002L,
                new BigDecimal("250.00"),
                "UPI",
                idempotencyKey
        );

        PaymentResponseDTO firstResponse =
                paymentService.createPayment(request);

        PaymentResponseDTO secondResponse =
                paymentService.createPayment(request);

        assertEquals(firstResponse.getId(), secondResponse.getId());

        assertEquals(
                firstResponse.getTransactionReference(),
                secondResponse.getTransactionReference()
        );

        assertEquals(PaymentStatus.SUCCESS, secondResponse.getStatus());

        Integer paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE idempotency_key = ?
                """,
                Integer.class,
                idempotencyKey
        );

        assertEquals(1, paymentCount);
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPaymentDetails() {

        String idempotencyKey = generateIdempotencyKey();

        PaymentRequestDTO firstRequest = createPaymentRequest(
                900003L,
                new BigDecimal("500.00"),
                "UPI",
                idempotencyKey
        );

        paymentService.createPayment(firstRequest);

        PaymentRequestDTO secondRequest = createPaymentRequest(
                900003L,
                new BigDecimal("700.00"),
                "UPI",
                idempotencyKey
        );

        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> paymentService.createPayment(secondRequest)
        );

        Integer paymentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM payments
                WHERE idempotency_key = ?
                """,
                Integer.class,
                idempotencyKey
        );

        assertEquals(1, paymentCount);
    }

    @Test
    void shouldProcessOnlyOnceWhenTwoRequestsArriveConcurrently()
            throws Exception {

        String idempotencyKey = generateIdempotencyKey();

        PaymentRequestDTO request = createPaymentRequest(
                900004L,
                new BigDecimal("850.00"),
                "UPI",
                idempotencyKey
        );

        CountDownLatch processorEntered = new CountDownLatch(1);
        CountDownLatch releaseProcessor = new CountDownLatch(1);

        AtomicInteger processorInvocationCount = new AtomicInteger(0);

        doAnswer(invocation -> {

            processorInvocationCount.incrementAndGet();

            processorEntered.countDown();

            boolean released = releaseProcessor.await(
                    15,
                    TimeUnit.SECONDS
            );

            if (!released) {
                throw new IllegalStateException(
                        "Timed out waiting to release payment processor"
                );
            }

            return PaymentProcessingResult.SUCCESS;

        }).when(paymentProcessor).processPayment(any(Payment.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {

            // Request A starts and reserves the payment in PostgreSQL.
            Future<PaymentResponseDTO> firstRequest =
                    executor.submit(
                            () -> paymentService.createPayment(request)
                    );

            // Wait until Request A reaches the payment processor.
            assertTrue(
                    processorEntered.await(10, TimeUnit.SECONDS),
                    "First request did not reach the payment processor"
            );

            // Request B arrives while Request A is still processing.
            PaymentResponseDTO secondResponse =
                    executor.submit(
                            () -> paymentService.createPayment(request)
                    ).get(10, TimeUnit.SECONDS);

            // Request B must retrieve the existing PROCESSING payment.
            assertEquals(
                    PaymentStatus.PROCESSING,
                    secondResponse.getStatus()
            );

            assertNotNull(secondResponse.getId());

            assertEquals(
                    1,
                    processorInvocationCount.get(),
                    "Payment processor must be invoked only once"
            );

            // Allow Request A to complete payment processing.
            releaseProcessor.countDown();

            PaymentResponseDTO firstResponse =
                    firstRequest.get(10, TimeUnit.SECONDS);

            assertEquals(
                    secondResponse.getId(),
                    firstResponse.getId()
            );

            assertEquals(
                    PaymentStatus.SUCCESS,
                    firstResponse.getStatus()
            );

            assertNotNull(firstResponse.getTransactionReference());

            Integer paymentCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM payments
                    WHERE idempotency_key = ?
                    """,
                    Integer.class,
                    idempotencyKey
            );

            assertEquals(1, paymentCount);

            String finalStatus = jdbcTemplate.queryForObject(
                    """
                    SELECT status
                    FROM payments
                    WHERE idempotency_key = ?
                    """,
                    String.class,
                    idempotencyKey
            );

            assertEquals("SUCCESS", finalStatus);

            assertEquals(
                    1,
                    processorInvocationCount.get()
            );

        } finally {

            // Always release the processor, even if an assertion fails.
            releaseProcessor.countDown();

            executor.shutdownNow();

            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private PaymentRequestDTO createPaymentRequest(
            Long orderId,
            BigDecimal amount,
            String paymentMethod,
            String idempotencyKey) {

        PaymentRequestDTO request = new PaymentRequestDTO();

        request.setOrderId(orderId);
        request.setAmount(amount);
        request.setCurrency("INR");
        request.setPaymentMethod(paymentMethod);
        request.setIdempotencyKey(idempotencyKey);

        return request;
    }

    private String generateIdempotencyKey() {
        return "integration-test-" + UUID.randomUUID();
    }
}