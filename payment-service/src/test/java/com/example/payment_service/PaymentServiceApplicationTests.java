package com.example.payment_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentServiceApplicationTests {

    @Test
    void applicationClassShouldHaveSpringBootConfiguration() {

        assertNotNull(PaymentServiceApplication.class);

        assertTrue(
                PaymentServiceApplication.class.isAnnotationPresent(
                        SpringBootApplication.class
                )
        );
    }
}