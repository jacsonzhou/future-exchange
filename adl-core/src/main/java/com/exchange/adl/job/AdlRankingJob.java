package com.exchange.adl.job;

import com.exchange.adl.service.AdlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * ADL排名定时更新任务
 */
@Slf4j
@Component
public class AdlRankingJob {
    
    @Autowired
    private AdlService adlService;
    
    // 支持的交易对列表
    private static final List<String> SYMBOLS = Arrays.asList("BTCUSDT", "ETHUSDT", "SOLUSDT");
    
    /**
     * 每5秒更新ADL排名队列
     */
    @Scheduled(fixedRate = 5000)
    public void updateAdlRanking() {
        for (String symbol : SYMBOLS) {
            try {
                // 计算多头ADL排名
                adlService.calculateAdlRanking(symbol, "LONG");
                
                // 计算空头ADL排名
                adlService.calculateAdlRanking(symbol, "SHORT");
                
            } catch (Exception e) {
                log.error("Failed to update ADL ranking for symbol={}", symbol, e);
            }
        }
    }
}
