package com.exchange.cfddealer.service;

import com.exchange.cfddealer.dto.ReferenceBookSnapshot;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference book cache.
 *
 * State machine:
 * MISSING -> READY -> (STALE | SHALLOW) -> READY
 */
@Slf4j
@Component
public class ReferenceBookStore {

    private final Map<String, StoredBook> books = new ConcurrentHashMap<>();

    public void upsert(ReferenceBookSnapshot incoming) {
        if (incoming == null) {
            return;
        }
        String symbol = normalizeSymbol(incoming.getSymbol());
        if (symbol == null) {
            return;
        }

        long now = System.currentTimeMillis();
        ReferenceBookSnapshot snapshot = snapshotForStore(incoming, symbol, now);
        long incomingOffset = snapshot.getOffset() == null ? 0L : snapshot.getOffset();
        long incomingEventTime = snapshot.getEventTime() == null ? now : snapshot.getEventTime();
        int depthLevels = depthLevels(snapshot);

        books.compute(symbol, (key, current) -> {
            if (isOutOfOrder(current, incomingOffset, incomingEventTime)) {
                if (log.isDebugEnabled()) {
                    log.debug("[CFD-DEALER] ignore out-of-order reference snapshot, symbol={}, incomingOffset={}, currentOffset={}, incomingEventTime={}, currentEventTime={}",
                        symbol,
                        incomingOffset,
                        current.offset,
                        incomingEventTime,
                        current.eventTime);
                }
                return current;
            }
            return new StoredBook(snapshot, now, incomingEventTime, incomingOffset, depthLevels);
        });
    }

    public LookupResult lookup(String symbol, long maxStaleMs, int minDepthLevels) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol == null) {
            return LookupResult.missing();
        }

        StoredBook stored = books.get(normalizedSymbol);
        if (stored == null) {
            return LookupResult.missing();
        }

        long now = System.currentTimeMillis();
        long stalenessMs = calculateStalenessMs(stored.eventTime, stored.updatedAt, now);
        int requiredDepthLevels = Math.max(1, minDepthLevels);
        ReferenceBookSnapshot snapshot = snapshotForRead(stored, now);

        if (maxStaleMs > 0 && stalenessMs > maxStaleMs) {
            return LookupResult.of(BookState.STALE, snapshot, stalenessMs, stored.depthLevels, requiredDepthLevels);
        }
        if (stored.depthLevels < requiredDepthLevels) {
            return LookupResult.of(BookState.SHALLOW, snapshot, stalenessMs, stored.depthLevels, requiredDepthLevels);
        }
        return LookupResult.of(BookState.READY, snapshot, stalenessMs, stored.depthLevels, requiredDepthLevels);
    }

    private boolean isOutOfOrder(StoredBook current, long incomingOffset, long incomingEventTime) {
        if (current == null) {
            return false;
        }
        if (incomingOffset > 0 && current.offset > 0) {
            return incomingOffset <= current.offset;
        }
        return incomingEventTime <= current.eventTime;
    }

    private ReferenceBookSnapshot snapshotForStore(ReferenceBookSnapshot source, String symbol, long now) {
        ReferenceBookSnapshot snapshot = cloneSnapshot(source);
        snapshot.setSymbol(symbol);
        long eventTime = snapshot.getEventTime() != null && snapshot.getEventTime() > 0
            ? snapshot.getEventTime()
            : now;
        snapshot.setEventTime(eventTime);
        snapshot.setStalenessMs(Math.max(0L, now - eventTime));
        return snapshot;
    }

    private ReferenceBookSnapshot snapshotForRead(StoredBook stored, long now) {
        ReferenceBookSnapshot snapshot = cloneSnapshot(stored.snapshot);
        snapshot.setStalenessMs(calculateStalenessMs(stored.eventTime, stored.updatedAt, now));
        return snapshot;
    }

    private ReferenceBookSnapshot cloneSnapshot(ReferenceBookSnapshot source) {
        ReferenceBookSnapshot target = new ReferenceBookSnapshot();
        target.setSymbol(source.getSymbol());
        target.setEventTime(source.getEventTime());
        target.setTopic(source.getTopic());
        target.setOffset(source.getOffset());
        target.setBestBid(source.getBestBid());
        target.setBestAsk(source.getBestAsk());
        target.setBidsTopN(copyLevels(source.getBidsTopN()));
        target.setAsksTopN(copyLevels(source.getAsksTopN()));
        target.setSource(source.getSource());
        target.setStalenessMs(source.getStalenessMs());
        return target;
    }

    private List<ReferenceBookSnapshot.PriceLevel> copyLevels(List<ReferenceBookSnapshot.PriceLevel> source) {
        List<ReferenceBookSnapshot.PriceLevel> copied = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return copied;
        }
        for (ReferenceBookSnapshot.PriceLevel item : source) {
            if (item == null) {
                continue;
            }
            ReferenceBookSnapshot.PriceLevel level = new ReferenceBookSnapshot.PriceLevel();
            level.setPrice(item.getPrice());
            level.setQuantity(item.getQuantity());
            copied.add(level);
        }
        return copied;
    }

    private int depthLevels(ReferenceBookSnapshot snapshot) {
        int bids = snapshot.getBidsTopN() == null ? 0 : snapshot.getBidsTopN().size();
        int asks = snapshot.getAsksTopN() == null ? 0 : snapshot.getAsksTopN().size();
        return Math.min(bids, asks);
    }

    private long calculateStalenessMs(long eventTime, long updatedAt, long now) {
        long baseline = eventTime > 0 ? eventTime : updatedAt;
        return Math.max(0L, now - baseline);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    public enum BookState {
        MISSING,
        READY,
        STALE,
        SHALLOW
    }

    @Getter
    public static final class LookupResult {
        private final BookState state;
        private final ReferenceBookSnapshot snapshot;
        private final long stalenessMs;
        private final int depthLevels;
        private final int requiredDepthLevels;

        private LookupResult(BookState state,
                             ReferenceBookSnapshot snapshot,
                             long stalenessMs,
                             int depthLevels,
                             int requiredDepthLevels) {
            this.state = state;
            this.snapshot = snapshot;
            this.stalenessMs = stalenessMs;
            this.depthLevels = depthLevels;
            this.requiredDepthLevels = requiredDepthLevels;
        }

        public static LookupResult missing() {
            return new LookupResult(BookState.MISSING, null, Long.MAX_VALUE, 0, 0);
        }

        public static LookupResult of(BookState state,
                                      ReferenceBookSnapshot snapshot,
                                      long stalenessMs,
                                      int depthLevels,
                                      int requiredDepthLevels) {
            return new LookupResult(state, snapshot, stalenessMs, depthLevels, requiredDepthLevels);
        }

        public boolean isReady() {
            return state == BookState.READY && snapshot != null;
        }
    }

    private static final class StoredBook {
        private final ReferenceBookSnapshot snapshot;
        private final long updatedAt;
        private final long eventTime;
        private final long offset;
        private final int depthLevels;

        private StoredBook(ReferenceBookSnapshot snapshot,
                           long updatedAt,
                           long eventTime,
                           long offset,
                           int depthLevels) {
            this.snapshot = snapshot;
            this.updatedAt = updatedAt;
            this.eventTime = eventTime;
            this.offset = offset;
            this.depthLevels = depthLevels;
        }
    }
}
