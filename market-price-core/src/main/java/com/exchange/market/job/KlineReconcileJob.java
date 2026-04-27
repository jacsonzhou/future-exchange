package com.exchange.market.job;

import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.service.KlineAuthorityService;
import com.exchange.market.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * K线权威对账任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineReconcileJob {

    private final KlineAuthorityService klineAuthorityService;
    private final KlineService klineService;
    private final ExternalMarketProperties externalMarketProperties;

    @Value("${market-data.kline.reconcile.enabled:true}")
    private boolean reconcileEnabled;

    @Value("${market-data.kline.reconcile.interval:1m}")
    private String reconcileInterval;

    @Value("${market-data.kline.reconcile.lookback-candles:240}")
    private int lookbackCandles;

    @Scheduled(
            initialDelayString = "${market-data.kline.reconcile.initial-delay-ms:45000}",
            fixedDelayString = "${market-data.kline.reconcile.interval-ms:300000}"
    )
    public void reconcile() {
        if (!reconcileEnabled || !klineAuthorityService.isAuthorityEnabled()) {
            return;
        }

        List<String> symbols = externalMarketProperties.getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            return;
        }

        String interval = normalizeInterval(reconcileInterval);
        long intervalMs = intervalToMillis(interval);
        if (intervalMs <= 0) {
            log.warn("[KlineReconcile] Unsupported interval={}, skip", interval);
            return;
        }

        String source = klineAuthorityService.getAuthoritySource();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            try {
                reconcileSymbol(source, symbol.trim().toUpperCase(Locale.ROOT), interval, intervalMs);
            } catch (Exception e) {
                log.error("[KlineReconcile] Reconcile failed, source={}, symbol={}, interval={}",
                        source, symbol, interval, e);
            }
        }
    }

    private void reconcileSymbol(String source, String symbol, String interval, long intervalMs) {
        Long latestAuthorityOpen = klineAuthorityService.findLatestOpenTime(source, symbol, interval);
        if (latestAuthorityOpen == null) {
            log.debug("[KlineReconcile] No authority data yet, source={}, symbol={}, interval={}",
                    source, symbol, interval);
            return;
        }

        Long latestHistoryOpen = klineService.getLatestOpenTime(symbol, interval);
        if (latestHistoryOpen == null) {
            log.warn("[KlineReconcile] History missing but authority exists, source={}, symbol={}, interval={}, latestAuthorityOpen={}",
                    source, symbol, interval, latestAuthorityOpen);
        } else {
            long delta = Math.abs(latestAuthorityOpen - latestHistoryOpen);
            if (delta > intervalMs) {
                log.warn("[KlineReconcile] Authority/history drift detected, source={}, symbol={}, interval={}, authorityOpen={}, historyOpen={}, deltaMs={}",
                        source, symbol, interval, latestAuthorityOpen, latestHistoryOpen, delta);
            }
        }

        long startOpen = Math.max(0L, latestAuthorityOpen - (long) Math.max(1, lookbackCandles) * intervalMs);
        List<Long> openTimes = klineAuthorityService.listOpenTimes(
                source,
                symbol,
                interval,
                startOpen,
                latestAuthorityOpen,
                Math.max(lookbackCandles + 8, 32)
        );
        if (openTimes.isEmpty()) {
            return;
        }

        List<Long> missingOpenTimes = findMissingOpenTimes(openTimes, intervalMs);
        if (!missingOpenTimes.isEmpty()) {
            List<Long> samples = missingOpenTimes.size() > 8
                    ? missingOpenTimes.subList(0, 8)
                    : missingOpenTimes;
            log.warn("[KlineReconcile] Continuity gap detected, source={}, symbol={}, interval={}, missingCount={}, sampleOpenTimes={}",
                    source, symbol, interval, missingOpenTimes.size(), samples);
        }

        Long watermark = klineAuthorityService.findWatermark(source, symbol, interval);
        if (watermark != null && watermark > 0 && latestAuthorityOpen - watermark > intervalMs * 3) {
            log.warn("[KlineReconcile] Watermark lag detected, source={}, symbol={}, interval={}, watermark={}, latestAuthorityOpen={}",
                    source, symbol, interval, watermark, latestAuthorityOpen);
        }
    }

    private List<Long> findMissingOpenTimes(List<Long> sortedOpenTimes, long intervalMs) {
        if (sortedOpenTimes == null || sortedOpenTimes.size() < 2) {
            return Collections.emptyList();
        }

        List<Long> missing = new ArrayList<>();
        long prev = sortedOpenTimes.get(0);
        for (int i = 1; i < sortedOpenTimes.size(); i++) {
            long current = sortedOpenTimes.get(i);
            if (current <= prev) {
                prev = current;
                continue;
            }

            long gap = current - prev;
            if (gap > intervalMs) {
                long expected = prev + intervalMs;
                while (expected < current) {
                    missing.add(expected);
                    if (missing.size() >= 128) {
                        return missing;
                    }
                    expected += intervalMs;
                }
            }
            prev = current;
        }
        return missing;
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return "1m";
        }
        return interval.trim();
    }

    private long intervalToMillis(String interval) {
        if (interval == null || interval.length() < 2) {
            return -1L;
        }

        int value;
        try {
            value = Integer.parseInt(interval.substring(0, interval.length() - 1));
        } catch (Exception e) {
            return -1L;
        }

        char unit = interval.charAt(interval.length() - 1);
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
