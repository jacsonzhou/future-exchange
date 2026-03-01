package com.exchange.cfddealer.task;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.service.LimitWorkingOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkingOrderTriggerTask {

    private final LimitWorkingOrderService limitWorkingOrderService;
    private final CfdDealerProperties properties;

    @Scheduled(fixedDelayString = "${cfd.dealer.limit-trigger-interval-ms:100}")
    public void trigger() {
        if (!properties.isPollingEnabled()) {
            return;
        }
        if (properties.getSymbols() == null || properties.getSymbols().isEmpty()) {
            return;
        }
        int totalTriggered = 0;
        for (String symbol : properties.getSymbols()) {
            int triggered = limitWorkingOrderService.triggerWorkingOrders(symbol);
            totalTriggered += triggered;
        }

        if (totalTriggered > 0) {
            log.info("[CFD-DEALER] working trigger tick completed, filledCount={}", totalTriggered);
        }
    }
}
