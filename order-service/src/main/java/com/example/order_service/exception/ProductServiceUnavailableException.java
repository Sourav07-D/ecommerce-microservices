package com.example.order_service.exception;

public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(Throwable cause) {
        super("Product Service is currently unavailable", cause);
    }
}