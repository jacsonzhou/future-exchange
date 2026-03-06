package com.exchange.cfddealer.service.impl;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.ReferenceBookSnapshot;
import com.exchange.cfddealer.service.ReferenceBookStore;
import com.exchange.cfddealer.service.ReferencePricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferencePricingServiceImpl implements ReferencePricingService {

    private static final String MODE_REDIS = "REDIS";
    private static final String MODE_MEMORY = "MEMORY";
    private static final String MODE_DUAL = "DUAL";
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal DUAL_DIFF_WARN_BPS = new BigDecimal("5");
    private static final long DUAL_COMPARE_LOG_INTERVAL = 200L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;
    private final ReferenceBookStore referenceBookStore;
    private final AtomicLong dualCompareCounter = new AtomicLong();

    @Override
    public ReferenceBookSnapshot getReferenceBook(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String pricingMode = normalizePricingMode(properties.getPricingMode());
        long maxStaleMs = properties.getReferenceMaxStaleMs();
        int minDepthLevels = properties.getReferenceMinDepthLevels();

        if (MODE_REDIS.equals(pricingMode)) {
            return getValidatedRedisSnapshot(normalizedSymbol, maxStaleMs, minDepthLevels, true);
        }

        ReferenceBookStore.LookupResult memoryLookup = referenceBookStore.lookup(normalizedSymbol, maxStaleMs, minDepthLevels);
        if (MODE_MEMORY.equals(pricingMode)) {
            if (memoryLookup.isReady()) {
                return memoryLookup.getSnapshot();
            }
            log.warn("[CFD-DEALER] memory reference unavailable, symbol={}, state={}, stalenessMs={}, depthLevels={}, requiredDepthLevels={}",
                normalizedSymbol,
                memoryLookup.getState(),
                memoryLookup.getStalenessMs(),
                memoryLookup.getDepthLevels(),
                memoryLookup.getRequiredDepthLevels());
            return null;
        }

        ReferenceBookSnapshot redisSnapshot = getValidatedRedisSnapshot(normalizedSymbol, maxStaleMs, minDepthLevels, false);
        if (memoryLookup.isReady()) {
            if (redisSnapshot != null) {
                logDualComparison(normalizedSymbol, memoryLookup.getSnapshot(), redisSnapshot);
            }
            return memoryLookup.getSnapshot();
        }

        if (redisSnapshot != null) {
            log.warn("[CFD-DEALER] pricing fallback to redis, symbol={}, mode={}, memoryState={}, memoryStalenessMs={}, memoryDepthLevels={}, requiredDepthLevels={}",
                normalizedSymbol,
                pricingMode,
                memoryLookup.getState(),
                memoryLookup.getStalenessMs(),
                memoryLookup.getDepthLevels(),
                memoryLookup.getRequiredDepthLevels());
            return redisSnapshot;
        }

        log.warn("[CFD-DEALER] reference book unavailable, symbol={}, mode={}, memoryState={}, memoryStalenessMs={}, memoryDepthLevels={}, requiredDepthLevels={}",
            normalizedSymbol,
            pricingMode,
            memoryLookup.getState(),
            memoryLookup.getStalenessMs(),
            memoryLookup.getDepthLevels(),
            memoryLookup.getRequiredDepthLevels());
        return null;
    }

    private ReferenceBookSnapshot getValidatedRedisSnapshot(String symbol,
                                                            long maxStaleMs,
                                                            int minDepthLevels,
                                                            boolean logWhenRejected) {
        ReferenceBookSnapshot snapshot = loadRedisSnapshot(symbol);
        if (snapshot == null) {
            return null;
        }

        long stalenessMs = snapshot.getStalenessMs() == null ? Long.MAX_VALUE : snapshot.getStalenessMs();
        int depthLevels = depthLevels(snapshot);
        int requiredDepthLevels = Math.max(1, minDepthLevels);

        if (maxStaleMs > 0 && stalenessMs > maxStaleMs) {
            if (logWhenRejected) {
                log.warn("[CFD-DEALER] redis reference stale, symbol={}, stalenessMs={}, maxStaleMs={}",
                    symbol, stalenessMs, maxStaleMs);
            }
            return null;
        }
        if (depthLevels < requiredDepthLevels) {
            if (logWhenRejected) {
                log.warn("[CFD-DEALER] redis reference depth insufficient, symbol={}, depthLevels={}, requiredDepthLevels={}",
                    symbol, depthLevels, requiredDepthLevels);
            }
            return null;
        }
        return snapshot;
    }

    private ReferenceBookSnapshot loadRedisSnapshot(String symbol) {
        String redisKey = properties.getReferenceRedisPrefix() + symbol;
        String raw = stringRedisTemplate.opsForValue().get(redisKey);
        if (raw == null) {
            return null;
        }

        try {
            ReferenceBookSnapshot snapshot = objectMapper.readValue(raw, ReferenceBookSnapshot.class);
            hydrateSnapshot(snapshot);
            return snapshot;
        } catch (Exception e) {
            log.error("[CFD-DEALER] parse reference book failed, symbol={}, key={}", symbol, redisKey, e);
            return null;
        }
    }

    private void hydrateSnapshot(ReferenceBookSnapshot snapshot) {
        long now = System.currentTimeMillis();
        long stalenessMs = calculateStalenessMs(snapshot, now);
        snapshot.setStalenessMs(stalenessMs);
    }

    private long calculateStalenessMs(ReferenceBookSnapshot snapshot, long now) {
        if (snapshot == null) {
            return Long.MAX_VALUE;
        }
        if (snapshot.getEventTime() != null && snapshot.getEventTime() > 0) {
            return Math.max(0L, now - snapshot.getEventTime());
        }
        if (snapshot.getStalenessMs() != null) {
            return Math.max(0L, snapshot.getStalenessMs());
        }
        return Long.MAX_VALUE;
    }

    private int depthLevels(ReferenceBookSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int bids = snapshot.getBidsTopN() == null ? 0 : snapshot.getBidsTopN().size();
        int asks = snapshot.getAsksTopN() == null ? 0 : snapshot.getAsksTopN().size();
        return Math.min(bids, asks);
    }

    private void logDualComparison(String symbol,
                                   ReferenceBookSnapshot memorySnapshot,
                                   ReferenceBookSnapshot redisSnapshot) {
        long compareCount = dualCompareCounter.incrementAndGet();

        BigDecimal memoryBid = parseDecimal(memorySnapshot.getBestBid());
        BigDecimal memoryAsk = parseDecimal(memorySnapshot.getBestAsk());
        BigDecimal redisBid = parseDecimal(redisSnapshot.getBestBid());
        BigDecimal redisAsk = parseDecimal(redisSnapshot.getBestAsk());

        BigDecimal bidDiffBps = absDiffBps(memoryBid, redisBid);
        BigDecimal askDiffBps = absDiffBps(memoryAsk, redisAsk);

        boolean warnDiff = bidDiffBps.compareTo(DUAL_DIFF_WARN_BPS) >= 0
            || askDiffBps.compareTo(DUAL_DIFF_WARN_BPS) >= 0;
        boolean periodicLog = compareCount % DUAL_COMPARE_LOG_INTERVAL == 0;
        boolean offsetMismatch = !Objects.equals(memorySnapshot.getOffset(), redisSnapshot.getOffset());
        if (!(warnDiff || periodicLog || offsetMismatch)) {
            return;
        }

        log.info("[CFD-DEALER] pricing dual compare, symbol={}, memoryBid={}, redisBid={}, memoryAsk={}, redisAsk={}, bidDiffBps={}, askDiffBps={}, memoryOffset={}, redisOffset={}, memoryStalenessMs={}, redisStalenessMs={}, memoryDepth={}, redisDepth={}",
            symbol,
            memorySnapshot.getBestBid(),
            redisSnapshot.getBestBid(),
            memorySnapshot.getBestAsk(),
            redisSnapshot.getBestAsk(),
            bidDiffBps.setScale(2, RoundingMode.HALF_UP),
            askDiffBps.setScale(2, RoundingMode.HALF_UP),
            memorySnapshot.getOffset(),
            redisSnapshot.getOffset(),
            memorySnapshot.getStalenessMs(),
            redisSnapshot.getStalenessMs(),
            depthLevels(memorySnapshot),
            depthLevels(redisSnapshot));
    }

    private BigDecimal absDiffBps(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal benchmark = first.compareTo(BigDecimal.ZERO) > 0 ? first : second;
        if (benchmark.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return first.subtract(second).abs()
            .divide(benchmark, 8, RoundingMode.HALF_UP)
            .multiply(BPS);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizePricingMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_DUAL;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (MODE_REDIS.equals(normalized) || MODE_MEMORY.equals(normalized) || MODE_DUAL.equals(normalized)) {
            return normalized;
        }
        log.warn("[CFD-DEALER] unknown pricing mode '{}', fallback to DUAL", mode);
        return MODE_DUAL;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
