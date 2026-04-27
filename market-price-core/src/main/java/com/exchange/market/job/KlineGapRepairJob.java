package com.exchange.market.job;

import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.service.KlineBackfillAsyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * K线缺口自动修复任务。
 *
 * 目标：处理外部数据源重启造成的中间断档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineGapRepairJob {

    private final BinanceKlineBackfillJob backfillJob;
    private final KlineBackfillAsyncTaskService taskService;
    private final ExternalMarketProperties externalMarketProperties;

    @Value("${market-data.kline.gap-repair.enabled:true}")
    private boolean enabled;

    @Value("${market-data.kline.gap-repair.interval:1m}")
    private String interval;

    @Value("${market-data.kline.gap-repair.lookback-candles:4320}")
    private int lookbackCandles;

    @Value("${market-data.kline.gap-repair.max-segments:64}")
    private int maxSegments;

    @Value("${market-data.kline.gap-repair.skip-when-resume-running:true}")
    private boolean skipWhenResumeRunning;

    @Scheduled(
            initialDelayString = "${market-data.kline.gap-repair.initial-delay-ms:120000}",
            fixedDelayString = "${market-data.kline.gap-repair.interval-ms:300000}"
    )
    public void repair() {
        if (!enabled) {
            return;
        }

        String normalizedInterval = normalizeInterval(interval);
        long intervalMs = intervalToMillis(normalizedInterval);
        if (intervalMs <= 0) {
            log.warn("[KlineGapRepair] unsupported interval={}, skip", normalizedInterval);
            return;
        }

        if (skipWhenResumeRunning && taskService.countRunningTasks() > 0) {
            log.debug("[KlineGapRepair] skip this cycle because resume task is running");
            return;
        }

        List<String> symbols = resolveSymbols();
        if (symbols.isEmpty()) {
            return;
        }

        int safeLookback = Math.max(60, Math.min(lookbackCandles, 200_000));
        int safeMaxSegments = Math.max(1, Math.min(maxSegments, 1_024));
        long now = System.currentTimeMillis();
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        long start = Math.max(0L, lastClosedOpen - safeLookback * intervalMs);

        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
            if (taskService.hasRunningTask(normalizedSymbol, normalizedInterval)) {
                continue;
            }
            try {
                BinanceKlineBackfillJob.MissingBackfillResult result = backfillJob.backfillMissingManual(
                        normalizedSymbol,
                        normalizedInterval,
                        start,
                        lastClosedOpen,
                        safeMaxSegments
                );
                if (result.getDetectedSegments() > 0) {
                    log.warn("[KlineGapRepair] repaired, symbol={}, interval={}, detected={}, repaired={}, imported={}",
                            normalizedSymbol, normalizedInterval,
                            result.getDetectedSegments(), result.getRepairedSegments(), result.getImported());
                }
            } catch (Exception e) {
                log.error("[KlineGapRepair] failed, symbol={}, interval={}",
                        normalizedSymbol, normalizedInterval, e);
            }
        }
    }

    private List<String> resolveSymbols() {
        Set<String> unique = new LinkedHashSet<>();
        List<String> symbols = externalMarketProperties.getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            unique.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        return unique.stream().toList();
    }

    private String normalizeInterval(String value) {
        if (value == null || value.isBlank()) {
            return "1m";
        }
        return value.trim();
    }

    private long alignToInterval(long timestamp, long intervalMs) {
        return (timestamp / intervalMs) * intervalMs;
    }

    private long intervalToMillis(String text) {
        if (text == null || text.length() < 2) {
            return -1L;
        }
        int value;
        try {
            value = Integer.parseInt(text.substring(0, text.length() - 1));
        } catch (Exception e) {
            return -1L;
        }
        char unit = text.charAt(text.length() - 1);
        return switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w' -> value * 7L * 86_400_000L;
            case 'M' -> value * 30L * 86_400_000L;
            default -> -1L;
        };
    }
}
