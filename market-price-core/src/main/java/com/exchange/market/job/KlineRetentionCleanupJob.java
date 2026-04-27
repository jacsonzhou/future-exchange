package com.exchange.market.job;

import com.exchange.market.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * K线历史保留清理任务（默认保留近一年）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineRetentionCleanupJob {

    private final KlineService klineService;

    @Value("${market-data.kline.retention.enabled:true}")
    private boolean retentionEnabled;

    @Value("${market-data.kline.retention.days:365}")
    private int retentionDays;

    /**
     * 每天执行一次老数据清理。
     */
    @Scheduled(
            initialDelayString = "${market-data.kline.retention.initial-delay-ms:120000}",
            fixedDelayString = "${market-data.kline.retention.interval-ms:86400000}"
    )
    public void cleanup() {
        if (!retentionEnabled) {
            return;
        }

        int safeDays = Math.max(30, Math.min(retentionDays, 3650));
        long now = System.currentTimeMillis();
        long cutoff = now - TimeUnit.DAYS.toMillis(safeDays);
        try {
            klineService.deleteKlinesBefore(cutoff);
            log.info("[KlineRetention] cleanup requested, retentionDays={}, cutoff={}", safeDays, cutoff);
        } catch (Exception e) {
            log.error("[KlineRetention] cleanup failed, retentionDays={}, cutoff={}", safeDays, cutoff, e);
        }
    }
}
