package com.exchange.market.service;

import com.exchange.market.model.Kline;
import com.exchange.market.repository.KlineAuthorityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * K线权威服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineAuthorityService {

    private final KlineAuthorityRepository authorityRepository;

    @Value("${market-data.kline.authority-enabled:false}")
    private boolean authorityEnabled;

    @Value("${market-data.kline.backfill-with-watermark:false}")
    private boolean backfillWithWatermark;

    @Value("${market-data.kline.authority-source:binance}")
    private String authoritySource;

    public enum AuthorityWriteResult {
        INSERTED,
        UPDATED,
        DUPLICATE,
        CONFLICT,
        SKIPPED
    }

    public boolean isAuthorityEnabled() {
        return authorityEnabled;
    }

    public boolean isBackfillWithWatermarkEnabled() {
        return authorityEnabled && backfillWithWatermark;
    }

    public String getAuthoritySource() {
        if (authoritySource == null || authoritySource.isBlank()) {
            return "binance";
        }
        return authoritySource.trim().toLowerCase(Locale.ROOT);
    }

    public AuthorityWriteResult saveRealtime(String source, Kline kline, boolean candleClosed, String rawPayloadJson) {
        if (!authorityEnabled || kline == null) {
            return AuthorityWriteResult.SKIPPED;
        }

        try {
            KlineAuthorityRepository.SaveResult saveResult = authorityRepository.save(source, kline, candleClosed, rawPayloadJson);
            if (candleClosed && saveResult != KlineAuthorityRepository.SaveResult.CONFLICT) {
                authorityRepository.upsertWatermark(source, kline.getSymbol(), kline.getInterval(), kline.getOpenTime(), kline.getCloseTime());
            }

            if (saveResult == KlineAuthorityRepository.SaveResult.CONFLICT) {
                long conflictCount = authorityRepository.countConflicts(source, kline.getSymbol(), kline.getInterval(), kline.getOpenTime());
                log.error("[KlineAuthority] Closed candle conflict detected, source={}, symbol={}, interval={}, openTime={}, conflictCount={}",
                        source, kline.getSymbol(), kline.getInterval(), kline.getOpenTime(), conflictCount);
                return AuthorityWriteResult.CONFLICT;
            }

            return map(saveResult);
        } catch (Exception e) {
            // 行情主链路优先，权威持久化失败时允许后续补齐任务修复。
            log.warn("[KlineAuthority] Save skipped due to persistence error, source={}, symbol={}, interval={}, openTime={}, reason={}",
                    source, kline.getSymbol(), kline.getInterval(), kline.getOpenTime(), e.getMessage());
            return AuthorityWriteResult.SKIPPED;
        }
    }

    public int saveBackfillBatch(String source, List<Kline> klines) {
        if (!authorityEnabled || klines == null || klines.isEmpty()) {
            return 0;
        }

        int accepted = 0;
        for (Kline kline : klines) {
            if (kline == null) {
                continue;
            }
            AuthorityWriteResult result = saveRealtime(source, kline, true, null);
            if (result == AuthorityWriteResult.INSERTED || result == AuthorityWriteResult.UPDATED) {
                accepted++;
            }
        }
        return accepted;
    }

    public Long findLatestOpenTime(String source, String symbol, String interval) {
        if (!authorityEnabled) {
            return null;
        }
        return authorityRepository.findLatestOpenTime(source, symbol, interval);
    }

    public Long findEarliestOpenTime(String source, String symbol, String interval) {
        if (!authorityEnabled) {
            return null;
        }
        return authorityRepository.findEarliestOpenTime(source, symbol, interval);
    }

    public Long findWatermark(String source, String symbol, String interval) {
        if (!isBackfillWithWatermarkEnabled()) {
            return null;
        }
        return authorityRepository.findWatermark(source, symbol, interval);
    }

    public void updateWatermark(String source, String symbol, String interval, long lastClosedOpenTime, long lastEventTime) {
        if (!isBackfillWithWatermarkEnabled()) {
            return;
        }
        authorityRepository.upsertWatermark(source, symbol, interval, lastClosedOpenTime, lastEventTime);
    }

    public List<Long> listOpenTimes(String source, String symbol, String interval, long startInclusive, long endInclusive, int limit) {
        if (!authorityEnabled || startInclusive > endInclusive) {
            return Collections.emptyList();
        }
        List<Long> openTimes = authorityRepository.listOpenTimes(source, symbol, interval, startInclusive, endInclusive, limit);
        if (openTimes == null || openTimes.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(openTimes);
    }

    private AuthorityWriteResult map(KlineAuthorityRepository.SaveResult result) {
        return switch (result) {
            case INSERTED -> AuthorityWriteResult.INSERTED;
            case UPDATED -> AuthorityWriteResult.UPDATED;
            case DUPLICATE -> AuthorityWriteResult.DUPLICATE;
            case CONFLICT -> AuthorityWriteResult.CONFLICT;
        };
    }
}
