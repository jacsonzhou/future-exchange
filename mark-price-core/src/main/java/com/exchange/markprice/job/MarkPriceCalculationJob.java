package com.exchange.markprice.job;

import com.exchange.markprice.service.MarkPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 标记价格计算定时任务
 */
@Slf4j
@Component
public class MarkPriceCalculationJob {

    @Autowired
    private MarkPriceService markPriceService;

    /**
     * 定时计算标记价格
     * 每1秒执行一次
     */
    @Scheduled(fixedRate = 1000)
    public void calculateMarkPrices() {
        try {
            markPriceService.batchCalculateMarkPrices();
        } catch (Exception e) {
            log.error("Failed to execute mark price calculation job", e);
        }
    }
}
