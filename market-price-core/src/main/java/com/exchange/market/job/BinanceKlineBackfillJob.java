package com.exchange.market.job;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.model.Kline;
import com.exchange.market.service.KlineAuthorityService;
import com.exchange.market.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binance 历史 K 线启动回补任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceKlineBackfillJob {

    private static final long SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);
    private static final int MAX_SYMBOL_RETRIES = 3;
    private static final int MAX_FETCH_RETRIES = 5;
    private static final int MAX_SAVE_RETRIES = 3;
    private static final long FETCH_RETRY_BASE_DELAY_MS = 1_000L;
    private static final long SAVE_RETRY_BASE_DELAY_MS = 1_000L;
    private static final long SYMBOL_RETRY_BASE_DELAY_MS = 3_000L;
    private static final long MAX_RETRY_SLEEP_MS = 120_000L;
    private static final Pattern BAN_UNTIL_PATTERN = Pattern.compile("banned until\\s+(\\d{10,13})");

    private final ExternalMarketProperties externalProperties;
    private final KlineService klineService;
    private final KlineAuthorityService klineAuthorityService;

    @Value("${market-data.kline.authority-enabled:false}")
    private boolean authorityEnabled;

    @Value("${market-data.kline.backfill-with-watermark:false}")
    private boolean backfillWithWatermark;

    @Value("${market-data.kline.authority-source:binance}")
    private String authoritySource;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Async("marketDataTaskExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!externalProperties.isEnabled() || !externalProperties.getBackfill().isEnabled()) {
            return;
        }

        try {
            long delay = Math.max(0L, externalProperties.getBackfill().getStartupDelayMs());
            if (delay > 0) {
                Thread.sleep(delay);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        List<String> symbols = resolveSymbols();
        if (symbols.isEmpty()) {
            log.info("[Backfill] No symbols configured, skip startup backfill");
            return;
        }

        String interval = normalizeInterval(externalProperties.getBackfill().getInterval());
        long intervalMs = intervalToMillis(interval);
        if (intervalMs <= 0) {
            log.warn("[Backfill] Unsupported interval={}, skip", interval);
            return;
        }

        for (String symbol : symbols) {
            boolean completed = false;
            for (int attempt = 1; attempt <= MAX_SYMBOL_RETRIES; attempt++) {
                try {
                    backfillSymbol(symbol, interval, intervalMs);
                    completed = true;
                    break;
                } catch (Exception e) {
                    if (attempt >= MAX_SYMBOL_RETRIES) {
                        log.error("[Backfill] Failed for symbol={} after {} attempts", symbol, attempt, e);
                        break;
                    }

                    long retryDelay = SYMBOL_RETRY_BASE_DELAY_MS * attempt;
                    log.warn("[Backfill] Attempt {}/{} failed for symbol={}, retry in {}ms",
                            attempt, MAX_SYMBOL_RETRIES, symbol, retryDelay, e);
                    sleepSilently(retryDelay);
                }
            }

            if (!completed) {
                log.warn("[Backfill] Skip symbol={} in this round after retries exhausted", symbol);
            }
        }
    }

    /**
     * 清库后重建历史K线（默认用于1m全量重建）。
     */
    public RebuildResult rebuildHistory(boolean truncateAll, String interval, int days, List<String> symbols) {
        String normalizedInterval = normalizeInterval(interval);
        long intervalMs = intervalToMillis(normalizedInterval);
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Unsupported interval: " + interval);
        }

        int safeDays = Math.max(1, Math.min(days, 3650));
        long now = System.currentTimeMillis();
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        long rangeStart = alignToInterval(now - TimeUnit.DAYS.toMillis(safeDays), intervalMs);
        if (lastClosedOpen < rangeStart) {
            lastClosedOpen = rangeStart;
        }

        if (truncateAll) {
            klineService.truncateAllKlines();
        }

        List<String> targets = resolveSymbols(symbols);
        if (targets.isEmpty()) {
            return RebuildResult.builder()
                    .interval(normalizedInterval)
                    .days(safeDays)
                    .startTime(rangeStart)
                    .endTime(lastClosedOpen)
                    .truncateAll(truncateAll)
                    .totalImported(0)
                    .importedBySymbol(Collections.emptyMap())
                    .failedSymbols(Collections.emptyList())
                    .build();
        }

        int limit = Math.max(1, Math.min(1500, externalProperties.getBackfill().getBatchLimit()));
        int totalImported = 0;
        Map<String, Integer> importedBySymbol = new LinkedHashMap<>();
        List<String> failedSymbols = new ArrayList<>();

        for (String symbol : targets) {
            try {
                int imported = backfillRange(symbol, normalizedInterval, intervalMs, rangeStart, lastClosedOpen, limit, now);
                importedBySymbol.put(symbol, imported);
                totalImported += imported;
            } catch (Exception e) {
                failedSymbols.add(symbol);
                log.error("[BackfillManual] rebuild failed, symbol={}, interval={}, start={}, end={}",
                        symbol, normalizedInterval, rangeStart, lastClosedOpen, e);
            }
        }

        return RebuildResult.builder()
                .interval(normalizedInterval)
                .days(safeDays)
                .startTime(rangeStart)
                .endTime(lastClosedOpen)
                .truncateAll(truncateAll)
                .totalImported(totalImported)
                .importedBySymbol(importedBySymbol)
                .failedSymbols(failedSymbols)
                .build();
    }

    /**
     * 手动回补指定时间区间。
     */
    public RangeBackfillResult backfillRangeManual(String symbol,
                                                   String interval,
                                                   long startTime,
                                                   long endTime,
                                                   boolean purgeRangeFirst) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(interval);
        long intervalMs = intervalToMillis(normalizedInterval);
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Unsupported interval: " + interval);
        }

        long now = System.currentTimeMillis();
        long safeStart = alignToInterval(Math.max(0L, startTime), intervalMs);
        long safeEnd = alignToInterval(Math.max(0L, endTime), intervalMs);
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        safeEnd = Math.min(safeEnd, lastClosedOpen);
        if (safeEnd < safeStart) {
            return RangeBackfillResult.builder()
                    .symbol(normalizedSymbol)
                    .interval(normalizedInterval)
                    .startTime(safeStart)
                    .endTime(safeEnd)
                    .purgeRangeFirst(purgeRangeFirst)
                    .imported(0)
                    .build();
        }

        if (purgeRangeFirst) {
            klineService.deleteKlines(normalizedSymbol, normalizedInterval, safeStart, safeEnd);
        }

        int limit = Math.max(1, Math.min(1500, externalProperties.getBackfill().getBatchLimit()));
        int imported = backfillRange(normalizedSymbol, normalizedInterval, intervalMs, safeStart, safeEnd, limit, now);
        return RangeBackfillResult.builder()
                .symbol(normalizedSymbol)
                .interval(normalizedInterval)
                .startTime(safeStart)
                .endTime(safeEnd)
                .purgeRangeFirst(purgeRangeFirst)
                .imported(imported)
                .build();
    }

    /**
     * 扫描区间缺口并逐段回补（用于中间断档手动修复）。
     */
    public MissingBackfillResult backfillMissingManual(String symbol,
                                                       String interval,
                                                       long startTime,
                                                       long endTime,
                                                       int maxSegments) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(interval);
        long intervalMs = intervalToMillis(normalizedInterval);
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Unsupported interval: " + interval);
        }

        long now = System.currentTimeMillis();
        long safeStart = alignToInterval(Math.max(0L, startTime), intervalMs);
        long safeEnd = alignToInterval(Math.max(0L, endTime), intervalMs);
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        safeEnd = Math.min(safeEnd, lastClosedOpen);
        if (safeEnd < safeStart) {
            return MissingBackfillResult.builder()
                    .symbol(normalizedSymbol)
                    .interval(normalizedInterval)
                    .startTime(safeStart)
                    .endTime(safeEnd)
                    .detectedSegments(0)
                    .repairedSegments(0)
                    .imported(0)
                    .segments(Collections.emptyList())
                    .build();
        }

        int safeMaxSegments = Math.max(1, Math.min(maxSegments, 512));
        List<Long> openTimes = klineService.listOpenTimes(normalizedSymbol, normalizedInterval, safeStart, safeEnd, 1_000_000);
        List<GapRange> gaps = detectGapRanges(openTimes, safeStart, safeEnd, intervalMs, safeMaxSegments);
        if (gaps.isEmpty()) {
            return MissingBackfillResult.builder()
                    .symbol(normalizedSymbol)
                    .interval(normalizedInterval)
                    .startTime(safeStart)
                    .endTime(safeEnd)
                    .detectedSegments(0)
                    .repairedSegments(0)
                    .imported(0)
                    .segments(Collections.emptyList())
                    .build();
        }

        int limit = Math.max(1, Math.min(1500, externalProperties.getBackfill().getBatchLimit()));
        int importedTotal = 0;
        int repaired = 0;
        List<SegmentResult> segmentResults = new ArrayList<>(gaps.size());
        for (GapRange gap : gaps) {
            try {
                int imported = backfillRange(
                        normalizedSymbol,
                        normalizedInterval,
                        intervalMs,
                        gap.startOpenTime(),
                        gap.endOpenTime(),
                        limit,
                        now
                );
                importedTotal += imported;
                repaired++;
                segmentResults.add(new SegmentResult(gap.startOpenTime(), gap.endOpenTime(), imported, true));
            } catch (Exception e) {
                segmentResults.add(new SegmentResult(gap.startOpenTime(), gap.endOpenTime(), 0, false));
                log.error("[BackfillManual] gap segment repair failed, symbol={}, interval={}, start={}, end={}",
                        normalizedSymbol, normalizedInterval, gap.startOpenTime(), gap.endOpenTime(), e);
            }
        }

        return MissingBackfillResult.builder()
                .symbol(normalizedSymbol)
                .interval(normalizedInterval)
                .startTime(safeStart)
                .endTime(safeEnd)
                .detectedSegments(gaps.size())
                .repairedSegments(repaired)
                .imported(importedTotal)
                .segments(segmentResults)
                .build();
    }

    private void backfillSymbol(String symbol, String interval, long intervalMs) {
        int limit = Math.max(1, Math.min(1500, externalProperties.getBackfill().getBatchLimit()));
        long now = System.currentTimeMillis();
        long initialDays = Math.max(1, externalProperties.getBackfill().getInitialDays());
        long backfillStart = alignToInterval(now - TimeUnit.DAYS.toMillis(initialDays), intervalMs);

        Long earliestOpenTime = resolveEarliestOpenTime(symbol, interval);
        Long latestOpenTime = resolveLatestOpenTime(symbol, interval);

        int importedBackward = 0;
        if (earliestOpenTime != null && earliestOpenTime > backfillStart) {
            long backwardEnd = earliestOpenTime - intervalMs;
            importedBackward = backfillRange(symbol, interval, intervalMs, backfillStart, backwardEnd, limit, now);
        }

        long forwardStart = latestOpenTime != null && latestOpenTime > 0
                ? latestOpenTime + intervalMs
                : backfillStart;
        Long watermark = resolveWatermark(symbol, interval);
        if (watermark != null && watermark > 0) {
            forwardStart = Math.max(forwardStart, watermark + intervalMs);
        }
        int importedForward = 0;
        if (forwardStart < now) {
            importedForward = backfillRange(symbol, interval, intervalMs, forwardStart, now, limit, now);
        }

        int total = importedBackward + importedForward;
        if (total == 0) {
            log.info("[Backfill] {} {} already up to date, latestOpenTime={}, earliestOpenTime={}",
                    symbol, interval, latestOpenTime, earliestOpenTime);
            return;
        }

        log.info("[Backfill] {} {} completed, importedBackward={}, importedForward={}, total={}",
                symbol, interval, importedBackward, importedForward, total);
    }

    private int backfillRange(String symbol, String interval, long intervalMs,
                              long rangeStartInclusive, long rangeEndInclusive,
                              int limit, long now) {
        if (rangeStartInclusive > rangeEndInclusive) {
            return 0;
        }

        long cursor = Math.max(0L, rangeStartInclusive);
        int total = 0;
        int rounds = 0;

        while (cursor <= rangeEndInclusive) {
            rounds++;
            List<Kline> batch = fetchKlines(symbol, interval, cursor, limit);
            if (batch.isEmpty()) {
                break;
            }

            List<Kline> valid = new ArrayList<>(batch.size());
            for (Kline kline : batch) {
                if (kline.getOpenTime() < cursor) {
                    continue;
                }
                if (kline.getOpenTime() > rangeEndInclusive) {
                    break;
                }
                // 仅导入已收线数据，避免当前未结束 K 线与实时流冲突。
                if (kline.getCloseTime() >= now) {
                    continue;
                }
                valid.add(kline);
            }

            if (valid.isEmpty()) {
                break;
            }

            saveKlinesWithRetry(symbol, interval, cursor, valid);
            total += valid.size();

            long lastOpen = valid.get(valid.size() - 1).getOpenTime();
            if (lastOpen < cursor || lastOpen >= rangeEndInclusive || valid.size() < limit) {
                break;
            }
            cursor = lastOpen + intervalMs;

            if (!sleepBetweenRequests()) {
                break;
            }
        }

        log.info("[Backfill] {} {} range completed, start={}, end={}, rounds={}, imported={}",
                symbol, interval, rangeStartInclusive, rangeEndInclusive, rounds, total);
        return total;
    }

    private void saveKlinesWithRetry(String symbol, String interval, long cursor, List<Kline> batch) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_SAVE_RETRIES; attempt++) {
            try {
                if (authorityEnabled) {
                    int accepted = klineAuthorityService.saveBackfillBatch(resolveAuthoritySource(), batch);
                    log.debug("[Backfill] authority batch saved, symbol={}, interval={}, cursor={}, accepted={}, total={}",
                            symbol, interval, cursor, accepted, batch.size());
                }
                klineService.saveKlines(batch);
                if (authorityEnabled && backfillWithWatermark && !batch.isEmpty()) {
                    Kline last = batch.get(batch.size() - 1);
                    klineAuthorityService.updateWatermark(
                            resolveAuthoritySource(),
                            symbol,
                            interval,
                            last.getOpenTime(),
                            last.getCloseTime()
                    );
                }
                return;
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt >= MAX_SAVE_RETRIES) {
                    break;
                }
                // 失败重试前先清理本批次范围，避免部分写入后重试产生重复open_time。
                if (!batch.isEmpty()) {
                    try {
                        long batchStart = batch.get(0).getOpenTime();
                        long batchEnd = batch.get(batch.size() - 1).getOpenTime();
                        klineService.deleteKlines(symbol, interval, batchStart, batchEnd);
                    } catch (Exception cleanupEx) {
                        log.warn("[Backfill] Batch cleanup before retry failed, symbol={}, interval={}, cursor={}",
                                symbol, interval, cursor, cleanupEx);
                    }
                }
                long delay = SAVE_RETRY_BASE_DELAY_MS * attempt;
                log.warn("[Backfill] Save retry {}/{} for {} {}, cursor={}, batchSize={}, delay={}ms",
                        attempt, MAX_SAVE_RETRIES, symbol, interval, cursor, batch.size(), delay, e);
                sleepSilently(delay);
            }
        }

        throw lastError != null ? lastError : new RuntimeException("Unknown backfill save failure");
    }

    private boolean sleepBetweenRequests() {
        long delay = Math.max(0L, externalProperties.getBackfill().getRequestDelayMs());
        if (delay <= 0) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sleepSilently(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Kline> fetchKlines(String symbol, String interval, long startTime, int limit) {
        String baseUrl = normalizeBaseUrl(externalProperties.getRestBaseUrl());
        String url = baseUrl + "/fapi/v1/klines?symbol=" + symbol +
                "&interval=" + interval +
                "&startTime=" + startTime +
                "&limit=" + limit;

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_FETCH_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                String body = response.body();
                if (statusCode == 200) {
                    JSONArray rows = JSON.parseArray(body);
                    if (rows == null || rows.isEmpty()) {
                        return List.of();
                    }
                    return mapRows(symbol, interval, rows);
                }

                long banDelayMs = parseBanDelayMs(body);
                if (isRetryableStatus(statusCode) && attempt < MAX_FETCH_RETRIES) {
                    long delay = computeRetryDelay(attempt, banDelayMs);
                    log.warn("[Backfill] Binance response code={}, symbol={}, interval={}, startTime={}, attempt={}/{}, retry in {}ms",
                            statusCode, symbol, interval, startTime, attempt, MAX_FETCH_RETRIES, delay);
                    sleepSilently(delay);
                    continue;
                }

                if (isRetryableStatus(statusCode)) {
                    throw new RuntimeException("Binance retryable response code=" + statusCode + ", body=" + trimBody(body));
                }

                throw new RuntimeException("Binance non-retryable response code=" + statusCode + ", body=" + trimBody(body));
            } catch (Exception e) {
                lastError = new RuntimeException("Failed to fetch Binance klines", e);
                if (attempt >= MAX_FETCH_RETRIES) {
                    break;
                }

                long delay = computeRetryDelay(attempt, 0L);
                log.warn("[Backfill] Fetch retry {}/{} for symbol={}, interval={}, startTime={}, retry in {}ms",
                        attempt, MAX_FETCH_RETRIES, symbol, interval, startTime, delay, e);
                sleepSilently(delay);
            }
        }

        log.error("[Backfill] Failed to fetch Binance klines, symbol={}, interval={}, startTime={}, retries={}",
                symbol, interval, startTime, MAX_FETCH_RETRIES, lastError);
        throw lastError != null ? lastError : new RuntimeException("Unknown fetch failure");
    }

    private List<Kline> mapRows(String symbol, String interval, JSONArray rows) {
        List<Kline> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JSONArray row = rows.getJSONArray(i);
            if (row == null || row.size() < 11) {
                continue;
            }

            long openTime = row.getLongValue(0);
            long closeTime = row.getLongValue(6);
            Kline kline = Kline.builder()
                    .symbol(symbol)
                    .interval(interval)
                    .openTime(openTime)
                    .closeTime(closeTime)
                    .openPrice(parseScaled(row.getString(1)))
                    .highPrice(parseScaled(row.getString(2)))
                    .lowPrice(parseScaled(row.getString(3)))
                    .closePrice(parseScaled(row.getString(4)))
                    .volume(parseScaled(row.getString(5)))
                    .quoteVolume(parseScaled(row.getString(7)))
                    .tradeCount(row.getIntValue(8))
                    .takerBuyVolume(parseScaled(row.getString(9)))
                    .takerBuyQuoteVolume(parseScaled(row.getString(10)))
                    .build();
            result.add(kline);
        }
        return result;
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 418 || statusCode == 429 || statusCode >= 500;
    }

    private long computeRetryDelay(int attempt, long banDelayMs) {
        long base = FETCH_RETRY_BASE_DELAY_MS * Math.max(1, attempt);
        long chosen = Math.max(base, banDelayMs);
        return Math.min(chosen, MAX_RETRY_SLEEP_MS);
    }

    private long parseBanDelayMs(String body) {
        if (body == null || body.isBlank()) {
            return 0L;
        }
        Matcher matcher = BAN_UNTIL_PATTERN.matcher(body);
        if (!matcher.find()) {
            return 0L;
        }
        try {
            long raw = Long.parseLong(matcher.group(1));
            long bannedUntilMs = raw < 1_000_000_000_000L ? raw * 1000L : raw;
            long delay = bannedUntilMs - System.currentTimeMillis() + 1000L;
            return Math.max(0L, delay);
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private String trimBody(String body) {
        if (body == null) {
            return "";
        }
        String text = body.strip();
        if (text.length() <= 300) {
            return text;
        }
        return text.substring(0, 300) + "...";
    }

    private List<String> resolveSymbols() {
        return resolveSymbols(null);
    }

    private List<String> resolveSymbols(List<String> requestedSymbols) {
        Set<String> normalized = new LinkedHashSet<>();
        if (requestedSymbols != null && !requestedSymbols.isEmpty()) {
            for (String symbol : requestedSymbols) {
                String value = normalizeSymbol(symbol);
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
            return new ArrayList<>(normalized);
        }

        if (externalProperties.getSymbols() == null) {
            return new ArrayList<>();
        }
        for (String symbol : externalProperties.getSymbols()) {
            String value = normalizeSymbol(symbol);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private List<GapRange> detectGapRanges(List<Long> openTimes,
                                           long startInclusive,
                                           long endInclusive,
                                           long intervalMs,
                                           int maxSegments) {
        if (startInclusive > endInclusive) {
            return Collections.emptyList();
        }

        List<Long> sorted = new ArrayList<>();
        if (openTimes != null && !openTimes.isEmpty()) {
            Set<Long> uniq = new LinkedHashSet<>(openTimes);
            for (Long value : uniq) {
                if (value == null) {
                    continue;
                }
                long ts = value;
                if (ts < startInclusive || ts > endInclusive) {
                    continue;
                }
                sorted.add(ts);
            }
            Collections.sort(sorted);
        }

        List<GapRange> gaps = new ArrayList<>();
        if (sorted.isEmpty()) {
            gaps.add(new GapRange(startInclusive, endInclusive));
            return gaps;
        }

        long cursor = startInclusive;
        for (long openTime : sorted) {
            if (openTime > cursor) {
                long gapEnd = openTime - intervalMs;
                if (gapEnd >= cursor) {
                    gaps.add(new GapRange(cursor, gapEnd));
                    if (gaps.size() >= maxSegments) {
                        return gaps;
                    }
                }
            }
            cursor = Math.max(cursor, openTime + intervalMs);
            if (cursor > endInclusive) {
                break;
            }
        }

        if (cursor <= endInclusive && gaps.size() < maxSegments) {
            gaps.add(new GapRange(cursor, endInclusive));
        }
        return gaps;
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "https://fapi.binance.com";
        }
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return "1m";
        }
        return interval.trim();
    }

    private Long resolveEarliestOpenTime(String symbol, String interval) {
        if (authorityEnabled) {
            Long value = klineAuthorityService.findEarliestOpenTime(resolveAuthoritySource(), symbol, interval);
            if (value != null) {
                return value;
            }
        }
        return klineService.getEarliestOpenTime(symbol, interval);
    }

    private Long resolveLatestOpenTime(String symbol, String interval) {
        if (authorityEnabled) {
            Long value = klineAuthorityService.findLatestOpenTime(resolveAuthoritySource(), symbol, interval);
            if (value != null) {
                return value;
            }
        }
        return klineService.getLatestOpenTime(symbol, interval);
    }

    private Long resolveWatermark(String symbol, String interval) {
        if (!authorityEnabled || !backfillWithWatermark) {
            return null;
        }
        return klineAuthorityService.findWatermark(resolveAuthoritySource(), symbol, interval);
    }

    private String resolveAuthoritySource() {
        if (authoritySource == null || authoritySource.isBlank()) {
            return "binance";
        }
        return authoritySource.trim().toLowerCase(Locale.ROOT);
    }

    private long parseScaled(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(value.trim())
                    .multiply(SCALE_BD)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long alignToInterval(long timestamp, long intervalMs) {
        return (timestamp / intervalMs) * intervalMs;
    }

    private long intervalToMillis(String interval) {
        if (interval == null || interval.length() < 2) {
            return -1L;
        }
        char unit = interval.charAt(interval.length() - 1);
        int value;
        try {
            value = Integer.parseInt(interval.substring(0, interval.length() - 1));
        } catch (Exception e) {
            return -1L;
        }

        return switch (unit) {
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w' -> value * 7L * 86_400_000L;
            case 'M' -> value * 30L * 86_400_000L;
            default -> -1L;
        };
    }

    private record GapRange(long startOpenTime, long endOpenTime) {
    }

    @lombok.Builder
    @lombok.Value
    public static class RebuildResult {
        String interval;
        int days;
        long startTime;
        long endTime;
        boolean truncateAll;
        int totalImported;
        Map<String, Integer> importedBySymbol;
        List<String> failedSymbols;
    }

    @lombok.Builder
    @lombok.Value
    public static class RangeBackfillResult {
        String symbol;
        String interval;
        long startTime;
        long endTime;
        boolean purgeRangeFirst;
        int imported;
    }

    @lombok.Builder
    @lombok.Value
    public static class MissingBackfillResult {
        String symbol;
        String interval;
        long startTime;
        long endTime;
        int detectedSegments;
        int repairedSegments;
        int imported;
        List<SegmentResult> segments;
    }

    @lombok.Value
    public static class SegmentResult {
        long startTime;
        long endTime;
        int imported;
        boolean success;
    }
}
