package com.example.order_service.client;

import com.example.order_service.exception.ProductNotFoundException;
import com.example.order_service.exception.ProductServiceUnavailableException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(
            @Value("${product.service.url}") String productServiceUrl) {

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        // Connection timeout: 2 seconds
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));

        // Response timeout: 3 seconds
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(productServiceUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Retry(name = "productService")
    @CircuitBreaker(
            name = "productService",
            fallbackMethod = "getProductFallback"
    )
    @Bulkhead(
            name = "productService",
            type = Bulkhead.Type.SEMAPHORE
    )
    public ProductResponseDTO getProductById(Long productId) {

        try {
            return restClient.get()
                    .uri("/api/products/{id}", productId)
                    .retrieve()
                    .body(ProductResponseDTO.class);

        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductNotFoundException(productId);

        } catch (ResourceAccessException ex) {
            throw new ProductServiceUnavailableException(ex);
        }
    }

    private ProductResponseDTO getProductFallback(
            Long productId, Throwable ex) {

        // Do not convert Bulkhead rejection into service unavailability.
        if (ex instanceof BulkheadFullException) {
            throw (BulkheadFullException) ex;
        }

        // Preserve Product Not Found behaviour.
        if (ex instanceof ProductNotFoundException) {
            throw (ProductNotFoundException) ex;
        }

        // Handle genuine downstream failures.
        throw new ProductServiceUnavailableException(ex);
    }
}