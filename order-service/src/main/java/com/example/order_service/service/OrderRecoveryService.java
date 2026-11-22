package com.example.order_service.service;

import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderRecoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(OrderRecoveryService.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final long staleThresholdMinutes;

    public OrderRecoveryService(
            OrderRepository orderRepository,
            OrderService orderService,
            @Value("${order.recovery.stale-threshold-minutes:5}")
            long staleThresholdMinutes
    ) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    public void recoverStaleOrders() {

        LocalDateTime cutoffTime =
                LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        List<Order> staleOrders =
                orderRepository
                        .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                                "PENDING",
                                cutoffTime
                        );

        if (staleOrders.isEmpty()) {
            log.debug("No stale pending orders found for recovery.");
            return;
        }

        log.info(
                "Found {} stale pending orders for recovery.",
                staleOrders.size()
        );

        for (Order order : staleOrders) {

            Long orderId = order.getId();

            try {
                OrderResponseDTO result =
                        orderService.reconcilePendingOrder(orderId);

                log.info(
                        "Recovery completed for order {}. Current status: {}",
                        orderId,
                        result.getStatus()
                );

            } catch (Exception ex) {

                log.error(
                        "Recovery failed for order {}. " +
                                "It will be retried during a future recovery cycle.",
                        orderId,
                        ex
                );
            }
        }
    }
}