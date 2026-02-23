package com.exchange.index.job;

import com.exchange.index.service.IndexPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 指数价格计算定时任务
 */
@Slf4j
@Component
public class IndexPriceCalculationJob {

    @Autowired
    private IndexPriceService indexPriceService;

    /**
     * 定时计算指数价格
     * 每5秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    public void calculateIndexPrices() {
        try {
            indexPriceService.batchCalculateIndexPrices();
        } catch (Exception e) {
            log.error("Failed to execute index price calculation job", e);
        }
    }
}
