package com.exchange.market.job;

import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.service.KlineBackfillAsyncTaskService;
import com.exchange.market.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * K线自动续跑任务。
 *
 * 场景：
 * - 服务重启后从最新open_time+interval继续回补
 * - 实时链路中断后自动追平
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineAutoResumeBackfillJob {

    private final KlineBackfillAsyncTaskService taskService;
    private final KlineService klineService;
    private final ExternalMarketProperties externalMarketProperties;

    @Value("${market-data.kline.resume.enabled:true}")
    private boolean enabled;

    @Value("${market-data.kline.resume.interval:1m}")
    private String interval;

    @Value("${market-data.kline.resume.chunk-candles:1440}")
    private int chunkCandles;

    @Value("${market-data.kline.resume.max-catchup-days:365}")
    private int maxCatchupDays;

    @Value("${market-data.kline.resume.max-segments-per-task:512}")
    private int maxSegmentsPerTask;

    @Value("${market-data.kline.resume.max-retries-per-segment:3}")
    private int maxRetriesPerSegment;

    @Value("${market-data.kline.resume.max-parallel-tasks:1}")
    private int maxParallelTasks;

    @Value("${market-data.kline.resume.missing-repair-enabled:true}")
    private boolean missingRepairEnabled;

    @Value("${market-data.kline.resume.missing-lookback-candles:4320}")
    private int missingLookbackCandles;

    @Value("${market-data.kline.resume.missing-max-segments:64}")
    private int missingMaxSegments;

    @Scheduled(
            initialDelayString = "${market-data.kline.resume.initial-delay-ms:60000}",
            fixedDelayString = "${market-data.kline.resume.interval-ms:60000}"
    )
    public void scheduleResume() {
        if (!enabled) {
            return;
        }

        String normalizedInterval = normalizeInterval(interval);
        long intervalMs = intervalToMillis(normalizedInterval);
        if (intervalMs <= 0) {
            log.warn("[KlineAutoResume] unsupported interval={}, skip", normalizedInterval);
            return;
        }

        List<String> symbols = resolveSymbols();
        if (symbols.isEmpty()) {
            return;
        }

        int runningTasks = taskService.countRunningTasks();
        int safeMaxParallel = Math.max(1, Math.min(maxParallelTasks, 16));
        int availableSlots = safeMaxParallel - runningTasks;
        if (availableSlots <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        List<SymbolLag> candidates = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
            if (taskService.hasRunningTask(normalizedSymbol, normalizedInterval)) {
                continue;
            }

            Long latest = klineService.getLatestOpenTime(normalizedSymbol, normalizedInterval);
            boolean needsCatchUp = latest == null || latest + intervalMs <= lastClosedOpen;
            if (!needsCatchUp) {
                continue;
            }
            long lagMs = latest == null ? Long.MAX_VALUE : Math.max(0L, lastClosedOpen - latest);
            candidates.add(new SymbolLag(normalizedSymbol, latest, lagMs));
        }

        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(Comparator.comparingLong(SymbolLag::lagMs).reversed());
        int submitted = 0;
        for (SymbolLag candidate : candidates) {
            if (submitted >= availableSlots) {
                break;
            }
            String normalizedSymbol = candidate.symbol();
            Long latest = candidate.latestOpenTime();

            KlineBackfillAsyncTaskService.ResumeTaskRequest req = new KlineBackfillAsyncTaskService.ResumeTaskRequest();
            req.setSymbol(normalizedSymbol);
            req.setInterval(normalizedInterval);
            req.setChunkCandles(chunkCandles);
            req.setMaxCatchupDays(maxCatchupDays);
            req.setMaxSegmentsPerTask(maxSegmentsPerTask);
            req.setMaxRetriesPerSegment(maxRetriesPerSegment);
            req.setMissingRepairEnabled(missingRepairEnabled);
            req.setMissingLookbackCandles(missingLookbackCandles);
            req.setMissingMaxSegments(missingMaxSegments);

            KlineBackfillAsyncTaskService.TaskSummary task = taskService.submitResumeTask(req);
            log.info("[KlineAutoResume] submit task, symbol={}, interval={}, taskId={}, latestOpenTime={}",
                    normalizedSymbol, normalizedInterval, task.getTaskId(), latest);
            submitted++;
        }
    }

    private record SymbolLag(String symbol, Long latestOpenTime, long lagMs) {
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
