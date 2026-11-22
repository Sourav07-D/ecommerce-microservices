package com.example.order_service.service;

import com.example.order_service.entity.Order;
import com.example.order_service.exception.OrderNotFoundException;
import com.example.order_service.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPersistenceService {

    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public OrderPersistenceService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order savePendingOrder(Order order) {

        order.setStatus("PENDING");

        Order savedOrder = orderRepository.save(order);

        entityManager.flush();
        entityManager.refresh(savedOrder);

        return savedOrder;
    }

    @Transactional
    public boolean updateOrderStatus(Long orderId, String status) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            return false;
        }

        if (!"CONFIRMED".equalsIgnoreCase(status)
                && !"CANCELLED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException(
                    "Invalid order status transition: " + status
            );
        }

        order.setStatus(status);

        return true;
    }
}