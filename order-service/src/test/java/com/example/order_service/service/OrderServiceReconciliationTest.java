package com.example.order_service.service;

import com.example.order_service.client.PaymentClient;
import com.example.order_service.client.PaymentRequestDTO;
import com.example.order_service.client.PaymentResponseDTO;
import com.example.order_service.client.ProductClient;
import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderItem;
import com.example.order_service.exception.PaymentServiceUnavailableException;
import com.example.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceReconciliationTest {

    private static final Long ORDER_ID = 501L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private OrderPersistenceService orderPersistenceService;

    private OrderService orderService;

    private Order order;

    @BeforeEach
    void setUp() {

        orderService = new OrderService(
                orderRepository,
                productClient,
                paymentClient,
                orderPersistenceService
        );

        order = createOrder();

        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(order));

        // This stub is only used when a matching successful payment exists.
        // Other reconciliation scenarios intentionally never update the order.
        lenient().when(orderPersistenceService.updateOrderStatus(
                eq(ORDER_ID),
                anyString()
        )).thenAnswer(invocation -> {

            String newStatus = invocation.getArgument(1);

            if ("PENDING".equals(order.getStatus())) {
                order.setStatus(newStatus);
                return true;
            }

            return false;
        });
    }

    private Order createOrder() {

        Order testOrder = new Order();

        ReflectionTestUtils.setField(
                testOrder,
                "id",
                ORDER_ID
        );

        testOrder.setCustomerId(101L);
        testOrder.setStatus("PENDING");

        OrderItem item = new OrderItem();

        item.setProductId(1L);
        item.setProductName("Wireless Mouse");
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setQuantity(2);

        testOrder.addItem(item);

        return testOrder;
    }

    private PaymentResponseDTO createPayment(
            Long orderId,
            String amount,
            String currency,
            String status
    ) {

        PaymentResponseDTO payment = new PaymentResponseDTO();

        payment.setId(1001L);
        payment.setOrderId(orderId);
        payment.setAmount(new BigDecimal(amount));
        payment.setCurrency(currency);
        payment.setStatus(status);
        payment.setPaymentMethod("UPI");

        return payment;
    }

    @Test
    void reconcilePendingOrder_whenExactlyOneMatchingSuccessfulPaymentExists_shouldConfirmOrder() {

        PaymentResponseDTO payment = createPayment(
                ORDER_ID,
                "200.00",
                "INR",
                "SUCCESS"
        );

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of(payment));

        var response =
                orderService.reconcilePendingOrder(ORDER_ID);

        assertEquals("CONFIRMED", response.getStatus());

        verify(paymentClient).getPaymentsByOrderId(ORDER_ID);

        verify(orderPersistenceService)
                .updateOrderStatus(ORDER_ID, "CONFIRMED");
    }

    @Test
    void reconcilePendingOrder_whenNoPaymentExists_shouldKeepOrderPending() {

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of());

        var response =
                orderService.reconcilePendingOrder(ORDER_ID);

        assertEquals("PENDING", response.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_whenPaymentIsPending_shouldKeepOrderPending() {

        PaymentResponseDTO payment = createPayment(
                ORDER_ID,
                "200.00",
                "INR",
                "PENDING"
        );

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of(payment));

        var response =
                orderService.reconcilePendingOrder(ORDER_ID);

        assertEquals("PENDING", response.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_whenPaymentStatusIsUnknown_shouldKeepOrderPending() {

        PaymentResponseDTO payment = createPayment(
                ORDER_ID,
                "200.00",
                "INR",
                "UNKNOWN"
        );

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of(payment));

        var response =
                orderService.reconcilePendingOrder(ORDER_ID);

        assertEquals("PENDING", response.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_whenPaymentServiceIsUnavailable_shouldKeepOrderPending() {

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenThrow(
                        new PaymentServiceUnavailableException(
                                "Payment Service unavailable."
                        )
                );

        assertThrows(
                PaymentServiceUnavailableException.class,
                () -> orderService.reconcilePendingOrder(ORDER_ID)
        );

        assertEquals("PENDING", order.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_whenMultipleSuccessfulPaymentsExist_shouldRequireManualInvestigation() {

        PaymentResponseDTO firstPayment = createPayment(
                ORDER_ID,
                "200.00",
                "INR",
                "SUCCESS"
        );

        PaymentResponseDTO secondPayment = createPayment(
                ORDER_ID,
                "200.00",
                "INR",
                "SUCCESS"
        );

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of(firstPayment, secondPayment));

        assertThrows(
                IllegalStateException.class,
                () -> orderService.reconcilePendingOrder(ORDER_ID)
        );

        assertEquals("PENDING", order.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_whenPaymentDetailsDoNotMatch_shouldKeepOrderPending() {

        PaymentResponseDTO wrongOrder = createPayment(
                999L,
                "200.00",
                "INR",
                "SUCCESS"
        );

        PaymentResponseDTO wrongAmount = createPayment(
                ORDER_ID,
                "500.00",
                "INR",
                "SUCCESS"
        );

        PaymentResponseDTO wrongCurrency = createPayment(
                ORDER_ID,
                "200.00",
                "USD",
                "SUCCESS"
        );

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of(
                        wrongOrder,
                        wrongAmount,
                        wrongCurrency
                ));

        var response =
                orderService.reconcilePendingOrder(ORDER_ID);

        assertEquals("PENDING", response.getStatus());

        verify(orderPersistenceService, never())
                .updateOrderStatus(any(), anyString());
    }

    @Test
    void reconcilePendingOrder_shouldNeverInitiateANewPayment() {

        when(paymentClient.getPaymentsByOrderId(ORDER_ID))
                .thenReturn(List.of());

        orderService.reconcilePendingOrder(ORDER_ID);

        verify(paymentClient, never())
                .processPayment(any(PaymentRequestDTO.class));

        verify(paymentClient)
                .getPaymentsByOrderId(ORDER_ID);
    }
}