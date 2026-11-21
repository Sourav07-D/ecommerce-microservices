package com.example.order_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderResponseDTO {

    private final Long id;
    private final Long customerId;
    private final String status;
    private final LocalDateTime createdAt;
    private final List<OrderItemResponseDTO> items;
    private final BigDecimal totalAmount;

    public OrderResponseDTO(
            Long id,
            Long customerId,
            String status,
            LocalDateTime createdAt,
            List<OrderItemResponseDTO> items,
            BigDecimal totalAmount) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.createdAt = createdAt;
        this.items = items;
        this.totalAmount = totalAmount;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<OrderItemResponseDTO> getItems() {
        return items;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
}