package com.example.order_service.repository;

import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderItem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void saveOrderWithItems_shouldPersistOrderAndItems() {

        Order order = new Order();
        order.setCustomerId(201L);

        OrderItem mouse = new OrderItem();
        mouse.setProductId(1L);
        mouse.setProductName("Wireless Mouse");
        mouse.setUnitPrice(new BigDecimal("999.00"));
        mouse.setQuantity(2);

        order.addItem(mouse);

        OrderItem keyboard = new OrderItem();
        keyboard.setProductId(3L);
        keyboard.setProductName("Mechanical Keyboard");
        keyboard.setUnitPrice(new BigDecimal("2499.00"));
        keyboard.setQuantity(1);

        order.addItem(keyboard);

        Order savedOrder = orderRepository.saveAndFlush(order);

        Long orderId = savedOrder.getId();

        entityManager.clear();

        Optional<Order> result = orderRepository.findById(orderId);

        assertThat(result).isPresent();

        Order retrievedOrder = result.orElseThrow();

        assertThat(retrievedOrder.getId()).isEqualTo(orderId);
        assertThat(retrievedOrder.getCustomerId()).isEqualTo(201L);
        assertThat(retrievedOrder.getStatus()).isEqualTo("PENDING");
        assertThat(retrievedOrder.getCreatedAt()).isNotNull();

        assertThat(retrievedOrder.getItems()).hasSize(2);

        assertThat(retrievedOrder.getItems())
                .extracting(OrderItem::getProductName)
                .containsExactlyInAnyOrder(
                        "Wireless Mouse",
                        "Mechanical Keyboard"
                );

        assertThat(retrievedOrder.getItems())
                .extracting(OrderItem::getQuantity)
                .containsExactlyInAnyOrder(2, 1);

        assertThat(retrievedOrder.getItems())
                .extracting(OrderItem::getUnitPrice)
                .containsExactlyInAnyOrder(
                        new BigDecimal("999.00"),
                        new BigDecimal("2499.00")
                );

        assertThat(retrievedOrder.getItems())
                .extracting(OrderItem::getProductId)
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void updateOrderStatus_shouldIncrementVersion() {

        Order order = new Order();
        order.setCustomerId(99999L);

        Order savedOrder = orderRepository.saveAndFlush(order);

        assertThat(savedOrder.getVersion()).isZero();

        Long orderId = savedOrder.getId();

        entityManager.clear();

        Order retrievedOrder = orderRepository.findById(orderId)
                .orElseThrow();

        retrievedOrder.setStatus("CONFIRMED");

        orderRepository.flush();

        assertThat(retrievedOrder.getVersion()).isEqualTo(1L);
        assertThat(retrievedOrder.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUpdates_shouldRejectStaleTransaction() {

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        Long orderId = transactionTemplate.execute(status -> {

            Order order = new Order();
            order.setCustomerId(88888L);

            Order savedOrder = orderRepository.saveAndFlush(order);

            return savedOrder.getId();
        });

        try {

            // Transaction A's snapshot: version 0.
            // This entity becomes detached after the transaction commits.
            Order staleOrder = transactionTemplate.execute(status ->
                    orderRepository.findById(orderId).orElseThrow()
            );

            assertThat(staleOrder).isNotNull();
            assertThat(staleOrder.getVersion()).isZero();

            // Transaction B updates the same order and commits version 1.
            transactionTemplate.execute(status -> {

                Order currentOrder = orderRepository.findById(orderId)
                        .orElseThrow();

                currentOrder.setStatus("CONFIRMED");

                orderRepository.flush();

                assertThat(currentOrder.getVersion()).isEqualTo(1L);

                return null;
            });

            // Transaction A now tries to update its stale version-0 copy.
            staleOrder.setStatus("CANCELLED");

            assertThatThrownBy(() ->
                    transactionTemplate.execute(status -> {

                        entityManager.merge(staleOrder);

                        entityManager.flush();

                        return null;
                    })
            ).isInstanceOfAny(
                    OptimisticLockException.class,
                    OptimisticLockingFailureException.class
            );

            // Verify that Transaction B's committed update remains intact.
            Order finalOrder = transactionTemplate.execute(status ->
                    orderRepository.findById(orderId).orElseThrow()
            );

            assertThat(finalOrder.getStatus()).isEqualTo("CONFIRMED");
            assertThat(finalOrder.getVersion()).isEqualTo(1L);

        } finally {

            // Clean up only the temporary record in order_test_db.
            transactionTemplate.execute(status -> {
                orderRepository.deleteById(orderId);
                orderRepository.flush();
                return null;
            });
        }
    }
}