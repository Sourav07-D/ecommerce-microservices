package com.example.order_service.client;

import java.math.BigDecimal;

public class PaymentRequestDTO {

    private Long orderId;
    private BigDecimal amount;
    private String currency;
    private String paymentMethod;
    private String idempotencyKey;

    public PaymentRequestDTO() {
    }

    public PaymentRequestDTO(
            Long orderId,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String idempotencyKey) {

        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}