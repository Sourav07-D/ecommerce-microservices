package com.example.order_service.service;

import com.example.order_service.client.PaymentClient;
import com.example.order_service.client.PaymentRequestDTO;
import com.example.order_service.client.PaymentResponseDTO;
import com.example.order_service.client.ProductClient;
import com.example.order_service.client.ProductResponseDTO;
import com.example.order_service.dto.OrderItemRequestDTO;
import com.example.order_service.dto.OrderRequestDTO;
import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.entity.OrderItem;
import com.example.order_service.exception.OrderNotFoundException;
import com.example.order_service.exception.PaymentServiceUnavailableException;
import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.exception.ProductServiceUnavailableException;
import com.example.order_service.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private OrderPersistenceService orderPersistenceService;

    @InjectMocks
    private OrderService orderService;

    // Helper: create product response
    private ProductResponseDTO createProduct(
            Long id, String name, String price) {

        ProductResponseDTO product = new ProductResponseDTO();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));

        return product;
    }

    // Helper: create order request
    private OrderRequestDTO createRequest(
            Long customerId,
            List<OrderItemRequestDTO> items) {

        OrderRequestDTO request = new OrderRequestDTO();
        request.setCustomerId(customerId);
        request.setItems(items);
        request.setPaymentMethod("UPI");

        return request;
    }

    // Helper: create order item request
    private OrderItemRequestDTO createItem(
            Long productId, int quantity) {

        OrderItemRequestDTO item = new OrderItemRequestDTO();
        item.setProductId(productId);
        item.setQuantity(quantity);

        return item;
    }

    // Helper: mock pending order persistence
    private void mockPendingOrderSave(Long orderId) {

        Order[] savedOrder = new Order[1];

        when(orderPersistenceService.savePendingOrder(any(Order.class)))
                .thenAnswer(invocation -> {

                    Order order = invocation.getArgument(0);

                    ReflectionTestUtils.setField(order, "id", orderId);
                    order.setStatus("PENDING");

                    savedOrder[0] = order;

                    return order;
                });

        // These operations are optional because some payment scenarios
        // throw an exception before order retrieval or status updates.
        lenient().when(orderRepository.findById(orderId))
                .thenAnswer(invocation ->
                        Optional.ofNullable(savedOrder[0])
                );

        lenient().when(orderPersistenceService.updateOrderStatus(
                eq(orderId),
                anyString()
        )).thenAnswer(invocation -> {

            String newStatus = invocation.getArgument(1);

            if (savedOrder[0] != null) {
                savedOrder[0].setStatus(newStatus);
            }

            return true;
        });
    }

    // Helper: create payment response
    private PaymentResponseDTO createPaymentResponse(String status) {

        PaymentResponseDTO response = new PaymentResponseDTO();
        response.setStatus(status);

        return response;
    }

    // Test 1: Successful order creation and payment
    @Test
    void createOrder_whenPaymentSucceeds_shouldConfirmOrder() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        OrderRequestDTO request = createRequest(
                101L,
                List.of(createItem(1L, 2))
        );

        mockPendingOrderSave(1L);

        when(paymentClient.processPayment(any(PaymentRequestDTO.class)))
                .thenReturn(createPaymentResponse("SUCCESS"));

        OrderResponseDTO response = orderService.createOrder(request);

        assertEquals(1L, response.getId());
        assertEquals(101L, response.getCustomerId());
        assertEquals("CONFIRMED", response.getStatus());
        assertEquals(1, response.getItems().size());

        assertEquals(
                new BigDecimal("1998.00"),
                response.getTotalAmount()
        );

        assertEquals(
                new BigDecimal("1998.00"),
                response.getItems().get(0).getSubtotal()
        );

        verify(orderPersistenceService)
                .savePendingOrder(any(Order.class));

        verify(paymentClient)
                .processPayment(any(PaymentRequestDTO.class));

        verify(orderPersistenceService)
                .updateOrderStatus(1L, "CONFIRMED");
    }

    // Test 2: Explicit payment failure
    @Test
    void createOrder_whenPaymentFails_shouldCancelOrder() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        OrderRequestDTO request = createRequest(
                102L,
                List.of(createItem(1L, 1))
        );

        mockPendingOrderSave(2L);

        when(paymentClient.processPayment(any(PaymentRequestDTO.class)))
                .thenReturn(createPaymentResponse("FAILED"));

        OrderResponseDTO response = orderService.createOrder(request);

        assertEquals("CANCELLED", response.getStatus());

        verify(orderPersistenceService)
                .updateOrderStatus(2L, "CANCELLED");
    }

    // Test 3: Payment Service unavailable
    @Test
    void createOrder_whenPaymentServiceUnavailable_shouldKeepOrderPending() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        OrderRequestDTO request = createRequest(
                103L,
                List.of(createItem(1L, 1))
        );

        mockPendingOrderSave(3L);

        when(paymentClient.processPayment(any(PaymentRequestDTO.class)))
                .thenThrow(new PaymentServiceUnavailableException(
                        "Payment outcome is unknown."
                ));

        assertThrows(
                PaymentServiceUnavailableException.class,
                () -> orderService.createOrder(request)
        );

        verify(orderPersistenceService)
                .savePendingOrder(any(Order.class));

        verify(orderPersistenceService, never())
                .updateOrderStatus(anyLong(), anyString());
    }

    // Test 4: Payment remains pending
    @Test
    void createOrder_whenPaymentIsPending_shouldKeepOrderPending() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        OrderRequestDTO request = createRequest(
                104L,
                List.of(createItem(1L, 1))
        );

        mockPendingOrderSave(4L);

        when(paymentClient.processPayment(any(PaymentRequestDTO.class)))
                .thenReturn(createPaymentResponse("PENDING"));

        assertThrows(
                PaymentServiceUnavailableException.class,
                () -> orderService.createOrder(request)
        );

        verify(orderPersistenceService, never())
                .updateOrderStatus(anyLong(), anyString());
    }

    // Test 5: Product does not exist
    @Test
    void createOrder_whenProductDoesNotExist_shouldNotSaveOrder() {

        when(productClient.getProductById(999L))
                .thenThrow(new ProductNotFoundException(999L));

        OrderRequestDTO request = createRequest(
                105L,
                List.of(createItem(999L, 1))
        );

        assertThrows(
                ProductNotFoundException.class,
                () -> orderService.createOrder(request)
        );

        verify(orderPersistenceService, never())
                .savePendingOrder(any(Order.class));

        verifyNoInteractions(paymentClient);
    }

    // Test 6: Product Service unavailable
    @Test
    void createOrder_whenProductServiceUnavailable_shouldNotSaveOrder() {

        when(productClient.getProductById(1L))
                .thenThrow(
                        new ProductServiceUnavailableException(
                                new RuntimeException(
                                        "Product Service is unavailable"
                                )
                        )
                );

        OrderRequestDTO request = createRequest(
                106L,
                List.of(createItem(1L, 1))
        );

        assertThrows(
                ProductServiceUnavailableException.class,
                () -> orderService.createOrder(request)
        );

        verify(orderPersistenceService, never())
                .savePendingOrder(any(Order.class));

        verifyNoInteractions(paymentClient);
    }

    // Test 7: Multiple products in one order
    @Test
    void createOrder_withMultipleProducts_shouldCalculateCorrectTotal() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        when(productClient.getProductById(3L))
                .thenReturn(createProduct(
                        3L, "Mechanical Keyboard", "2499.00"));

        OrderRequestDTO request = createRequest(
                107L,
                List.of(
                        createItem(1L, 2),
                        createItem(3L, 1)
                )
        );

        mockPendingOrderSave(5L);

        when(paymentClient.processPayment(any(PaymentRequestDTO.class)))
                .thenReturn(createPaymentResponse("SUCCESS"));

        OrderResponseDTO response = orderService.createOrder(request);

        assertEquals(2, response.getItems().size());

        assertEquals(
                new BigDecimal("1998.00"),
                response.getItems().get(0).getSubtotal()
        );

        assertEquals(
                new BigDecimal("2499.00"),
                response.getItems().get(1).getSubtotal()
        );

        assertEquals(
                new BigDecimal("4497.00"),
                response.getTotalAmount()
        );

        assertEquals("CONFIRMED", response.getStatus());

        verify(orderPersistenceService)
                .updateOrderStatus(5L, "CONFIRMED");
    }

    // Test 8: Second product does not exist
    @Test
    void createOrder_whenSecondProductDoesNotExist_shouldNotSaveOrder() {

        when(productClient.getProductById(1L))
                .thenReturn(createProduct(
                        1L, "Wireless Mouse", "999.00"));

        when(productClient.getProductById(999L))
                .thenThrow(new ProductNotFoundException(999L));

        OrderRequestDTO request = createRequest(
                108L,
                List.of(
                        createItem(1L, 2),
                        createItem(999L, 1)
                )
        );

        assertThrows(
                ProductNotFoundException.class,
                () -> orderService.createOrder(request)
        );

        verify(orderPersistenceService, never())
                .savePendingOrder(any(Order.class));

        verifyNoInteractions(paymentClient);
    }

    // Test 9: Retrieve an existing order
    @Test
    void getOrderById_whenOrderExists_shouldReturnOrderDetailsAndCalculateTotal() {

        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 1L);
        order.setCustomerId(101L);

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

        when(orderRepository.findById(1L))
                .thenReturn(Optional.of(order));

        OrderResponseDTO response = orderService.getOrderById(1L);

        assertEquals(1L, response.getId());
        assertEquals(101L, response.getCustomerId());
        assertEquals(2, response.getItems().size());

        assertEquals(
                new BigDecimal("1998.00"),
                response.getItems().get(0).getSubtotal()
        );

        assertEquals(
                new BigDecimal("2499.00"),
                response.getItems().get(1).getSubtotal()
        );

        assertEquals(
                new BigDecimal("4497.00"),
                response.getTotalAmount()
        );

        verify(orderRepository).findById(1L);
    }

    // Test 10: Requested order does not exist
    @Test
    void getOrderById_whenOrderDoesNotExist_shouldThrowOrderNotFoundException() {

        when(orderRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> orderService.getOrderById(999L)
        );

        verify(orderRepository).findById(999L);
    }

    // Test 11: Retrieval uses stored prices without Product Service
    @Test
    void getOrderById_shouldUseStoredPricesWithoutCallingProductService() {

        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 2L);
        order.setCustomerId(102L);

        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setProductName("Wireless Mouse");
        item.setUnitPrice(new BigDecimal("899.00"));
        item.setQuantity(2);
        order.addItem(item);

        when(orderRepository.findById(2L))
                .thenReturn(Optional.of(order));

        OrderResponseDTO response = orderService.getOrderById(2L);

        assertEquals(2L, response.getId());
        assertEquals(102L, response.getCustomerId());
        assertEquals(1, response.getItems().size());

        assertEquals(
                new BigDecimal("1798.00"),
                response.getTotalAmount()
        );

        verify(orderRepository).findById(2L);
        verifyNoInteractions(productClient);
    }
}
