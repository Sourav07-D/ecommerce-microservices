
        package com.example.order_service.service;

import com.example.order_service.client.PaymentClient;
import com.example.order_service.client.PaymentRequestDTO;
import com.example.order_service.client.PaymentResponseDTO;
import com.example.order_service.client.ProductClient;
import com.example.order_service.client.ProductResponseDTO;
import com.example.order_service.dto.OrderItemRequestDTO;
import com.example.order_service.dto.OrderItemResponseDTO;
import com.example.order_service.dto.OrderRequestDTO;
import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderItem;
import com.example.order_service.exception.OrderNotFoundException;
import com.example.order_service.exception.PaymentServiceUnavailableException;
import com.example.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final PaymentClient paymentClient;
    private final OrderPersistenceService orderPersistenceService;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            PaymentClient paymentClient,
            OrderPersistenceService orderPersistenceService) {

        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.paymentClient = paymentClient;
        this.orderPersistenceService = orderPersistenceService;
    }

    public OrderResponseDTO createOrder(OrderRequestDTO request) {

        Order order = new Order();
        order.setCustomerId(request.getCustomerId());

        List<OrderItemResponseDTO> responseItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        // Step 1: Fetch product details and calculate total
        for (OrderItemRequestDTO itemRequest : request.getItems()) {

            ProductResponseDTO product =
                    productClient.getProductById(itemRequest.getProductId());

            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(itemRequest.getQuantity());

            order.addItem(orderItem);

            BigDecimal subtotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

            totalAmount = totalAmount.add(subtotal);

            responseItems.add(new OrderItemResponseDTO(
                    product.getId(),
                    product.getName(),
                    product.getPrice(),
                    itemRequest.getQuantity(),
                    subtotal
            ));
        }

        // Step 2: Save order as PENDING in a short transaction
        Order savedOrder =
                orderPersistenceService.savePendingOrder(order);

        // Step 3: Call Payment Service outside the database transaction
        PaymentRequestDTO paymentRequest = new PaymentRequestDTO(
                savedOrder.getId(),
                totalAmount,
                "INR",
                request.getPaymentMethod(),
                "order-payment-" + savedOrder.getId()
        );

        PaymentResponseDTO paymentResponse =
                paymentClient.processPayment(paymentRequest);

        if (paymentResponse == null || paymentResponse.getStatus() == null) {
            throw new PaymentServiceUnavailableException(
                    "Payment outcome is unknown. The order remains PENDING."
            );
        }

        String finalStatus;

        // Step 4: Handle explicit payment outcome
        switch (paymentResponse.getStatus().toUpperCase()) {

            case "SUCCESS":
                finalStatus = "CONFIRMED";
                break;

            case "FAILED":
                finalStatus = "CANCELLED";
                break;

            case "PENDING":
                throw new PaymentServiceUnavailableException(
                        "Payment is still pending. The order remains PENDING."
                );

            default:
                throw new PaymentServiceUnavailableException(
                        "Unexpected payment response. The order remains PENDING."
                );
        }

        // Step 5: Update order status in another short transaction
        orderPersistenceService.updateOrderStatus(
                savedOrder.getId(),
                finalStatus
        );

        // Return the actual persisted order status
        return getOrderById(savedOrder.getId());
    }

    /*
     * Reconcile an order whose payment outcome was previously uncertain.
     * This method NEVER initiates a new payment.
     */
    public OrderResponseDTO reconcilePendingOrder(Long orderId) {

        // Step 1: Retrieve the existing order and its stored item snapshots
        OrderResponseDTO order = getOrderById(orderId);

        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException(
                    "Only PENDING orders can be reconciled."
            );
        }

        // Step 2: Retrieve existing payments without creating a payment
        List<PaymentResponseDTO> payments =
                paymentClient.getPaymentsByOrderId(orderId);

        // Step 3: Find a payment matching this order and its total
        List<PaymentResponseDTO> successfulPayments = payments.stream()
                .filter(payment -> payment.getOrderId() != null
                        && payment.getOrderId().equals(orderId))
                .filter(payment -> payment.getAmount() != null
                        && payment.getAmount().compareTo(order.getTotalAmount()) == 0)
                .filter(payment -> "INR".equalsIgnoreCase(payment.getCurrency()))
                .filter(payment -> "SUCCESS".equalsIgnoreCase(payment.getStatus()))
                .toList();

        // No successful payment exists.
        // Keep the order PENDING and do not initiate another payment.
        if (successfulPayments.isEmpty()) {
            return order;
        }

        // More than one successful payment is an unexpected duplicate.
        if (successfulPayments.size() > 1) {
            throw new IllegalStateException(
                    "Multiple successful payments found for order "
                            + orderId + ". Manual investigation is required."
            );
        }

        // Step 4: Confirm the order only after successful verification
        orderPersistenceService.updateOrderStatus(orderId, "CONFIRMED");

        // Step 5: Return the actual persisted order status
        return getOrderById(orderId);
    }

    @Transactional(readOnly = true)
    public OrderResponseDTO getOrderById(Long orderId) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderItemResponseDTO> responseItems = order.getItems()
                .stream()
                .map(item -> {
                    BigDecimal subtotal = item.getUnitPrice()
                            .multiply(BigDecimal.valueOf(item.getQuantity()));

                    return new OrderItemResponseDTO(
                            item.getProductId(),
                            item.getProductName(),
                            item.getUnitPrice(),
                            item.getQuantity(),
                            subtotal
                    );
                })
                .collect(Collectors.toList());

        BigDecimal totalAmount = responseItems.stream()
                .map(OrderItemResponseDTO::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrderResponseDTO(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getCreatedAt(),
                responseItems,
                totalAmount
        );
    }
}
