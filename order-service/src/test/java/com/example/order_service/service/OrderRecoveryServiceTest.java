package com.example.order_service.service;

import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderRecoveryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    private OrderRecoveryService orderRecoveryService;

    @BeforeEach
    void setUp() {
        orderRecoveryService = new OrderRecoveryService(
                orderRepository,
                orderService,
                5
        );
    }

    private Order createOrder(Long id) {
        Order order = new Order();

        ReflectionTestUtils.setField(order, "id", id);
        order.setStatus("PENDING");

        return order;
    }

    private OrderResponseDTO createResponse(String status) {
        return new OrderResponseDTO(
                1L,
                101L,
                status,
                LocalDateTime.now(),
                List.of(),
                BigDecimal.ZERO
        );
    }

    @Test
    void recoverStaleOrders_whenPendingOrdersExist_shouldReconcileThem() {

        Order firstOrder = createOrder(101L);
        Order secondOrder = createOrder(102L);

        when(orderRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of(firstOrder, secondOrder));

        when(orderService.reconcilePendingOrder(101L))
                .thenReturn(createResponse("CONFIRMED"));

        when(orderService.reconcilePendingOrder(102L))
                .thenReturn(createResponse("PENDING"));

        orderRecoveryService.recoverStaleOrders();

        verify(orderService).reconcilePendingOrder(101L);
        verify(orderService).reconcilePendingOrder(102L);

        verify(orderRepository)
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                );
    }

    @Test
    void recoverStaleOrders_whenNoPendingOrdersExist_shouldNotReconcileAnything() {

        when(orderRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of());

        orderRecoveryService.recoverStaleOrders();

        verify(orderRepository)
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                );

        verifyNoInteractions(orderService);
    }

    @Test
    void recoverStaleOrders_whenOneOrderFails_shouldContinueWithNextOrder() {

        Order firstOrder = createOrder(201L);
        Order secondOrder = createOrder(202L);

        when(orderRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of(firstOrder, secondOrder));

        when(orderService.reconcilePendingOrder(201L))
                .thenThrow(new RuntimeException("Payment Service unavailable"));

        when(orderService.reconcilePendingOrder(202L))
                .thenReturn(createResponse("CONFIRMED"));

        orderRecoveryService.recoverStaleOrders();

        verify(orderService).reconcilePendingOrder(201L);
        verify(orderService).reconcilePendingOrder(202L);
    }

    @Test
    void recoverStaleOrders_shouldUseConfiguredStaleThreshold() {

        when(orderRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of());

        LocalDateTime beforeInvocation = LocalDateTime.now();

        orderRecoveryService.recoverStaleOrders();

        LocalDateTime afterInvocation = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoffCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);

        verify(orderRepository)
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq("PENDING"),
                        cutoffCaptor.capture()
                );

        LocalDateTime actualCutoff = cutoffCaptor.getValue();

        assertFalse(
                actualCutoff.isBefore(beforeInvocation.minusMinutes(5))
        );

        assertFalse(
                actualCutoff.isAfter(afterInvocation.minusMinutes(5))
        );
    }
}