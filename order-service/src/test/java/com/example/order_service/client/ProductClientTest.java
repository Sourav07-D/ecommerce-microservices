package com.example.order_service.client;

import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.exception.ProductServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProductClientTest {

    private HttpServer mockServer;
    private ProductClient productClient;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0
        );

        mockServer.start();

        int port = mockServer.getAddress().getPort();

        productClient = new ProductClient(
                "http://localhost:" + port
        );
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void getProductById_whenProductExists_shouldReturnProduct()
            throws IOException {

        mockServer.createContext("/api/products/1", exchange -> {
            String response = """
                    {
                        "id": 1,
                        "name": "Wireless Mouse",
                        "price": 999.00
                    }
                    """;

            byte[] responseBytes =
                    response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json"
            );

            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });

        ProductResponseDTO product =
                productClient.getProductById(1L);

        assertNotNull(product);
        assertEquals(1L, product.getId());
        assertEquals("Wireless Mouse", product.getName());
        assertEquals(
                new java.math.BigDecimal("999.00"),
                product.getPrice()
        );
    }

    @Test
    void getProductById_whenProductDoesNotExist_shouldThrowException() {

        mockServer.createContext("/api/products/9999", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThrows(
                ProductNotFoundException.class,
                () -> productClient.getProductById(9999L)
        );
    }

    @Test
    void getProductById_whenServiceIsUnavailable_shouldThrowException() {
        productClient = new ProductClient(
                "http://localhost:1"
        );

        assertThrows(
                ProductServiceUnavailableException.class,
                () -> productClient.getProductById(1L)
        );
    }
}