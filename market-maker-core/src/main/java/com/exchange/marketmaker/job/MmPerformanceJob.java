package com.exchange.marketmaker.job;

import com.exchange.marketmaker.service.MmPerformanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 做市商考核定时任务
 */
@Slf4j
@Component
public class MmPerformanceJob {

    @Autowired
    private MmPerformanceService mmPerformanceService;

    /**
     * 每日凌晨2点执行
     * 计算前一日的做市商考核指标
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void calculateDailyPerformance() {
        String yesterday = LocalDate.now().minusDays(1).toString();

        log.info("[MM-Job] Start daily performance calculation, date={}", yesterday);

        try {
            Integer count = mmPerformanceService.batchCalculateDailyPerformance(yesterday);
            log.info("[MM-Job] Daily performance calculation finished, count={}", count);

        } catch (Exception e) {
            log.error("[MM-Job] Daily performance calculation failed", e);
        }
    }

    /**
     * 每小时执行
     * 实时监控做市商报价质量
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void monitorQuoteQuality() {
        log.info("[MM-Job] Start quote quality monitoring");

        try {
            // TODO: 监控做市商报价质量
            // 1. 检查买卖价差是否超标
            // 2. 检查挂单深度是否达标
            // 3. 检查挂单时间占比
            // 4. 发现异常则告警

        } catch (Exception e) {
            log.error("[MM-Job] Quote quality monitoring failed", e);
        }
    }
}
