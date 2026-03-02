package com.exchange.market.job;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.model.Kline;
import com.exchange.market.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Binance 历史 K 线启动回补任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceKlineBackfillJob {

    private static final long SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);

    private final ExternalMarketProperties externalProperties;
    private final KlineService klineService;

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
            try {
                backfillSymbol(symbol, interval, intervalMs);
            } catch (Exception e) {
                log.error("[Backfill] Failed for symbol={}", symbol, e);
            }
        }
    }

    private void backfillSymbol(String symbol, String interval, long intervalMs) {
        int limit = Math.max(1, Math.min(1500, externalProperties.getBackfill().getBatchLimit()));
        long now = System.currentTimeMillis();
        long initialDays = Math.max(1, externalProperties.getBackfill().getInitialDays());
        long backfillStart = alignToInterval(now - TimeUnit.DAYS.toMillis(initialDays), intervalMs);

        Long earliestOpenTime = klineService.getEarliestOpenTime(symbol, interval);
        Long latestOpenTime = klineService.getLatestOpenTime(symbol, interval);

        int importedBackward = 0;
        if (earliestOpenTime != null && earliestOpenTime > backfillStart) {
            long backwardEnd = earliestOpenTime - intervalMs;
            importedBackward = backfillRange(symbol, interval, intervalMs, backfillStart, backwardEnd, limit, now);
        }

        long forwardStart = latestOpenTime != null && latestOpenTime > 0
                ? latestOpenTime + intervalMs
                : backfillStart;
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

            klineService.saveKlines(valid);
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

    private List<Kline> fetchKlines(String symbol, String interval, long startTime, int limit) {
        try {
            String baseUrl = normalizeBaseUrl(externalProperties.getRestBaseUrl());
            String url = baseUrl + "/fapi/v1/klines?symbol=" + symbol +
                    "&interval=" + interval +
                    "&startTime=" + startTime +
                    "&limit=" + limit;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Backfill] Binance response code={}, symbol={}, body={}",
                        response.statusCode(), symbol, response.body());
                return List.of();
            }

            JSONArray rows = JSON.parseArray(response.body());
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }

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
        } catch (Exception e) {
            log.error("[Backfill] Failed to fetch Binance klines, symbol={}, interval={}, startTime={}",
                    symbol, interval, startTime, e);
            return List.of();
        }
    }

    private List<String> resolveSymbols() {
        List<String> result = new ArrayList<>();
        if (externalProperties.getSymbols() == null) {
            return result;
        }
        for (String symbol : externalProperties.getSymbols()) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            result.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        return result;
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
}
