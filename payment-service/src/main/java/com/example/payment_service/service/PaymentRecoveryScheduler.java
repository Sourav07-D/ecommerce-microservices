package com.example.payment_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "payment.recovery.enabled",
        havingValue = "true"
)
public class PaymentRecoveryScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(PaymentRecoveryScheduler.class);

    private final PaymentRecoveryService paymentRecoveryService;

    public PaymentRecoveryScheduler(
            PaymentRecoveryService paymentRecoveryService) {
        this.paymentRecoveryService = paymentRecoveryService;
    }

    @Scheduled(
            fixedDelayString = "${payment.recovery.fixed-delay-ms:60000}"
    )
    public void recoverStalePayments() {

        try {
            int recoveredCount =
                    paymentRecoveryService.recoverStalePayments();

            logger.info(
                    "Payment recovery cycle completed. Recovered payments: {}",
                    recoveredCount
            );

        } catch (Exception exception) {
            logger.error(
                    "Payment recovery cycle failed",
                    exception
            );
        }
    }
}