
        package com.example.order_service.repository;

import com.example.order_service.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = {"items"})
    List<Order> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            String status,
            LocalDateTime cutoffTime
    );
}
