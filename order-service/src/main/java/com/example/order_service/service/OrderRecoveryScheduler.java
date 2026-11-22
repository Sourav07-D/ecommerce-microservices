package com.example.order_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "order.recovery",
        name = "enabled",
        havingValue = "true"
)
public class OrderRecoveryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OrderRecoveryScheduler.class);

    private final OrderRecoveryService orderRecoveryService;

    public OrderRecoveryScheduler(
            OrderRecoveryService orderRecoveryService
    ) {
        this.orderRecoveryService = orderRecoveryService;
    }

    @Scheduled(
            fixedDelayString = "${order.recovery.fixed-delay-ms:60000}"
    )
    public void runRecovery() {

        log.info("Starting scheduled order recovery.");

        try {
            orderRecoveryService.recoverStaleOrders();

        } catch (Exception ex) {
            log.error(
                    "Unexpected error during scheduled order recovery.",
                    ex
            );
        }

        log.info("Scheduled order recovery cycle finished.");
    }
}