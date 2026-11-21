package com.example.order_service.controller;

import com.example.order_service.dto.OrderRequestDTO;
import com.example.order_service.dto.OrderItemResponseDTO;
import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.exception.GlobalExceptionHandler;
import com.example.order_service.exception.OrderNotFoundException;
import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.exception.ProductServiceUnavailableException;
import com.example.order_service.service.OrderService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrder_withValidRequest_shouldReturn201() throws Exception {

        OrderItemResponseDTO item = new OrderItemResponseDTO(
                1L,
                "Wireless Mouse",
                new BigDecimal("999.00"),
                2,
                new BigDecimal("1998.00")
        );

        OrderResponseDTO response = new OrderResponseDTO(
                100L,
                101L,
                "PENDING",
                LocalDateTime.of(2026, 10, 27, 10, 0),
                List.of(item),
                new BigDecimal("1998.00")
        );

        when(orderService.createOrder(any(OrderRequestDTO.class)))
                .thenReturn(response);

        String requestJson = """
                {
                    "customerId": 101,
                    "paymentMethod": "UPI",
                    "items": [
                        {
                            "productId": 1,
                            "quantity": 2
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.customerId").value(101))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].productName")
                        .value("Wireless Mouse"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(1998.00));
    }

    @Test
    void createOrder_withMissingCustomerId_shouldReturn400() throws Exception {

        String requestJson = """
                {
                    "paymentMethod": "UPI",
                    "items": [
                        {
                            "productId": 1,
                            "quantity": 2
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail")
                        .value("Request validation failed"))
                .andExpect(jsonPath("$.errors.customerId")
                        .value("Customer ID is required"));
    }

    @Test
    void getOrderById_whenOrderExists_shouldReturn200() throws Exception {

        OrderItemResponseDTO item = new OrderItemResponseDTO(
                1L,
                "Wireless Mouse",
                new BigDecimal("999.00"),
                1,
                new BigDecimal("999.00")
        );

        OrderResponseDTO response = new OrderResponseDTO(
                5L,
                107L,
                "PENDING",
                LocalDateTime.of(2026, 10, 26, 10, 29),
                List.of(item),
                new BigDecimal("999.00")
        );

        when(orderService.getOrderById(5L)).thenReturn(response);

        mockMvc.perform(get("/api/orders/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.customerId").value(107))
                .andExpect(jsonPath("$.items[0].productName")
                        .value("Wireless Mouse"))
                .andExpect(jsonPath("$.totalAmount").value(999.00));
    }

    @Test
    void getOrderById_whenOrderDoesNotExist_shouldReturn404() throws Exception {

        when(orderService.getOrderById(9999L))
                .thenThrow(new OrderNotFoundException(9999L));

        mockMvc.perform(get("/api/orders/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order Not Found"));
    }

    @Test
    void createOrder_whenProductDoesNotExist_shouldReturn404() throws Exception {

        when(orderService.createOrder(any(OrderRequestDTO.class)))
                .thenThrow(new ProductNotFoundException(9999L));

        String requestJson = """
                {
                    "customerId": 101,
                    "paymentMethod": "UPI",
                    "items": [
                        {
                            "productId": 9999,
                            "quantity": 1
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product Not Found"));
    }

    @Test
    void createOrder_whenProductServiceIsUnavailable_shouldReturn503()
            throws Exception {

        when(orderService.createOrder(any(OrderRequestDTO.class)))
                .thenThrow(new ProductServiceUnavailableException(
                        new RuntimeException("Connection refused")));

        String requestJson = """
                {
                    "customerId": 101,
                    "paymentMethod": "UPI",
                    "items": [
                        {
                            "productId": 1,
                            "quantity": 1
                        }
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title")
                        .value("Product Service Unavailable"));
    }

    @Test
    void reconcileOrder_whenOptimisticLockingOccurs_shouldReturn409()
            throws Exception {

        when(orderService.reconcilePendingOrder(5L))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        Order.class,
                        5L
                ));

        mockMvc.perform(post("/api/orders/5/reconcile"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Concurrent Order Modification"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("This order was modified by another request. Please retrieve the latest order and retry your operation."));
    }
}