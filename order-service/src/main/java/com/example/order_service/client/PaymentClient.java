package com.example.order_service.client;

import com.example.order_service.exception.PaymentServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(
            @Value("${payment.service.url}") String paymentServiceUrl) {

        this.restClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();
    }

    public PaymentResponseDTO processPayment(PaymentRequestDTO request) {

        try {
            return restClient.post()
                    .uri("/api/payments")
                    .body(request)
                    .retrieve()
                    .body(PaymentResponseDTO.class);

        } catch (ResourceAccessException | HttpServerErrorException ex) {

            throw new PaymentServiceUnavailableException(
                    "Payment Service is unavailable or its response is uncertain.",
                    ex
            );
        }
    }

    public List<PaymentResponseDTO> getPaymentsByOrderId(Long orderId) {

        try {
            List<PaymentResponseDTO> payments = restClient.get()
                    .uri("/api/payments/order/{orderId}", orderId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PaymentResponseDTO>>() {});

            return payments != null ? payments : List.of();

        } catch (ResourceAccessException | HttpServerErrorException ex) {

            throw new PaymentServiceUnavailableException(
                    "Unable to verify payment status with Payment Service.",
                    ex
            );
        }
    }
}