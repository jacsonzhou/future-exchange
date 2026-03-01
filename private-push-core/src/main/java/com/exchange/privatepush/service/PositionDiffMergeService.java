package com.exchange.privatepush.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 持仓推送 diff 合并器。
 *
 * 在短窗口内合并同一 user+symbol+side 的多次更新，
 * 以降低 WebSocket 推送风暴，同时保留最新状态与变更字段。
 *
 * 大规模场景优化：
 * 1) 分片并行 flush，避免全量扫描单线程瓶颈
 * 2) lastDispatchedState 带 TTL 清理，避免长期内存膨胀
 * 3) 指标计数，便于线上观测合并效果与丢弃率
 */
@Slf4j
@Service
public class PositionDiffMergeService {

    private final MessageDispatcher messageDispatcher;

    @Value("${private.push.position-merge.window-ms:120}")
    private long mergeWindowMs;

    @Value("${private.push.position-merge.max-delay-ms:500}")
    private long maxDelayMs;

    @Value("${private.push.position-merge.max-events:32}")
    private int maxMergedEvents;

    @Value("${private.push.position-merge.flush-interval-ms:50}")
    private long flushIntervalMs;

    @Value("${private.push.position-merge.flush-workers:4}")
    private int flushWorkers;

    @Value("${private.push.position-merge.last-state-ttl-ms:1800000}")
    private long lastStateTtlMs;

    @Value("${private.push.position-merge.stats-interval-ms:10000}")
    private long statsIntervalMs;

    private volatile List<ConcurrentHashMap<String, MergeBucket>> pendingBucketsByShard = List.of();
    private volatile List<ConcurrentHashMap<String, LastDispatchedState>> lastDispatchedStatesByShard = List.of();
    private final List<ScheduledExecutorService> flushExecutors = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService statsExecutor;

    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder flushedBucketCount = new LongAdder();
    private final LongAdder mergedEventCount = new LongAdder();
    private final LongAdder droppedNoChangeCount = new LongAdder();
    private final LongAdder dispatchErrorCount = new LongAdder();
    private final LongAdder cleanedStateCount = new LongAdder();


    public PositionDiffMergeService(MessageDispatcher messageDispatcher) {
        this.messageDispatcher = messageDispatcher;
    }

    @PostConstruct
    public void init() {
        int shardCount = Math.max(1, flushWorkers);
        List<ConcurrentHashMap<String, MergeBucket>> pendingShards = new ArrayList<>(shardCount);
        List<ConcurrentHashMap<String, LastDispatchedState>> dispatchedShards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            pendingShards.add(new ConcurrentHashMap<>());
            dispatchedShards.add(new ConcurrentHashMap<>());
        }
        pendingBucketsByShard = pendingShards;
        lastDispatchedStatesByShard = dispatchedShards;

        for (int shard = 0; shard < shardCount; shard++) {
            final int shardIndex = shard;
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "position-diff-merge-" + shardIndex);
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(
                () -> flushDueBuckets(shardIndex),
                Math.max(10, flushIntervalMs),
                Math.max(10, flushIntervalMs),
                TimeUnit.MILLISECONDS
            );
            flushExecutors.add(executor);
        }

        statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "position-diff-merge-stats");
            t.setDaemon(true);
            return t;
        });
        statsExecutor.scheduleAtFixedRate(
            this::logStatsAndCleanupStates,
            Math.max(1000, statsIntervalMs),
            Math.max(1000, statsIntervalMs),
            TimeUnit.MILLISECONDS
        );

        log.info("[PositionDiffMergeService] Initialized, window={}ms, maxDelay={}ms, maxEvents={}, flush={}ms, workers={}, stateTtl={}ms, stats={}ms",
            mergeWindowMs, maxDelayMs, maxMergedEvents, flushIntervalMs, shardCount, lastStateTtlMs, statsIntervalMs);
    }

    @PreDestroy
    public void destroy() {
        flushAllDueBuckets();
        for (ScheduledExecutorService executor : flushExecutors) {
            executor.shutdown();
        }
        flushExecutors.clear();
        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }
    }

    public boolean enqueue(Long userId, JSONObject positionUpdate) {
        if (userId == null || positionUpdate == null) {
            return false;
        }
        String symbol = normalize(positionUpdate.getString("s"));
        if (symbol.isBlank()) {
            return false;
        }

        String side = normalize(positionUpdate.getString("ps"));
        String key = buildKey(userId, symbol, side);
        int shardIndex = shardIndex(key);
        ConcurrentHashMap<String, MergeBucket> shardBuckets = pendingBucketsByShard.get(shardIndex);
        long now = System.currentTimeMillis();

        MergeBucket mergedBucket = shardBuckets.compute(key, (k, bucket) -> {
            if (bucket == null) {
                bucket = new MergeBucket(userId, symbol, side, now);
            }
            bucket.merge(positionUpdate, now);
            return bucket;
        });

        enqueuedCount.increment();
        if (mergedBucket != null && mergedBucket.shouldFlushImmediately(maxMergedEvents)) {
            flushBucketIfNeeded(shardIndex, key, mergedBucket, now);
        }
        return true;
    }

    private void flushDueBuckets(int shardIndex) {
        List<ConcurrentHashMap<String, MergeBucket>> pendingShards = pendingBucketsByShard;
        if (pendingShards.isEmpty() || shardIndex < 0 || shardIndex >= pendingShards.size()) {
            return;
        }

        ConcurrentHashMap<String, MergeBucket> shardBuckets = pendingShards.get(shardIndex);
        if (shardBuckets.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        shardBuckets.forEach((key, bucket) -> {
            if (!bucket.isDue(now, mergeWindowMs, maxDelayMs, maxMergedEvents)) {
                return;
            }
            flushBucketIfNeeded(shardIndex, key, bucket, now);
        });
    }

    private void flushAllDueBuckets() {
        List<ConcurrentHashMap<String, MergeBucket>> pendingShards = pendingBucketsByShard;
        for (int i = 0; i < pendingShards.size(); i++) {
            final int shardIndex = i;
            ConcurrentHashMap<String, MergeBucket> shardBuckets = pendingShards.get(i);
            if (shardBuckets.isEmpty()) {
                continue;
            }
            shardBuckets.forEach((key, bucket) -> flushBucketIfNeeded(shardIndex, key, bucket, System.currentTimeMillis()));
        }
    }

    private void flushBucketIfNeeded(int shardIndex, String key, MergeBucket bucket, long now) {
        ConcurrentHashMap<String, MergeBucket> shardBuckets = pendingBucketsByShard.get(shardIndex);
        if (!shardBuckets.remove(key, bucket)) {
            return;
        }
        dispatchMergedBucket(shardIndex, key, bucket, now);
    }

    private void dispatchMergedBucket(int shardIndex, String key, MergeBucket bucket, long now) {
        MergeSnapshot snapshot = bucket.snapshot(now);
        ConcurrentHashMap<String, LastDispatchedState> stateShard = lastDispatchedStatesByShard.get(shardIndex);
        LastDispatchedState previousState = stateShard.get(key);
        PositionState previous = previousState != null ? previousState.state : null;
        PositionState latest = snapshot.latest;
        JSONArray changed = latest.diffFields(previous);
        if (changed.isEmpty()) {
            droppedNoChangeCount.increment();
            stateShard.put(key, new LastDispatchedState(latest, snapshot.flushAt));
            return;
        }

        JSONObject mergedPayload = latest.toPayload();
        mergedPayload.put("changed", changed);
        mergedPayload.put("mergedCount", snapshot.mergeCount);
        mergedPayload.put("E", snapshot.lastEventTime);
        mergedPayload.put("seq", snapshot.lastSeq);

        try {
            messageDispatcher.sendToUser(snapshot.userId, "position", mergedPayload);
            flushedBucketCount.increment();
            mergedEventCount.add(snapshot.mergeCount);
            stateShard.put(key, new LastDispatchedState(latest, snapshot.flushAt));
        } catch (Exception e) {
            dispatchErrorCount.increment();
            log.warn("[PositionDiffMergeService] Dispatch failed, key={}, shard={}, userId={}",
                key, shardIndex, snapshot.userId, e);
        }
    }

    private void logStatsAndCleanupStates() {
        int pendingBuckets = 0;
        for (ConcurrentHashMap<String, MergeBucket> shard : pendingBucketsByShard) {
            pendingBuckets += shard.size();
        }

        long now = System.currentTimeMillis();
        int[] cleanedThisRound = new int[]{0};
        int cachedStates = 0;

        for (int i = 0; i < lastDispatchedStatesByShard.size(); i++) {
            ConcurrentHashMap<String, LastDispatchedState> stateShard = lastDispatchedStatesByShard.get(i);
            ConcurrentHashMap<String, MergeBucket> pendingShard = pendingBucketsByShard.get(i);
            cachedStates += stateShard.size();

            stateShard.forEach((key, state) -> {
                if (now - state.dispatchedAt < lastStateTtlMs) {
                    return;
                }
                if (pendingShard.containsKey(key)) {
                    return;
                }
                if (stateShard.remove(key, state)) {
                    cleanedThisRound[0]++;
                    cleanedStateCount.increment();
                }
            });
        }

        long enqueued = enqueuedCount.sumThenReset();
        long flushed = flushedBucketCount.sumThenReset();
        long mergedEvents = mergedEventCount.sumThenReset();
        long droppedNoChange = droppedNoChangeCount.sumThenReset();
        long dispatchErrors = dispatchErrorCount.sumThenReset();

        if (enqueued == 0 && flushed == 0 && mergedEvents == 0
            && droppedNoChange == 0 && dispatchErrors == 0
            && pendingBuckets == 0 && cleanedThisRound[0] == 0) {
            return;
        }

        log.info("[PositionDiffMergeService] Stats enqueued={}, flushed={}, mergedEvents={}, droppedNoChange={}, dispatchErrors={}, pendingBuckets={}, cachedStates={}, cleanedStates={}",
            enqueued, flushed, mergedEvents, droppedNoChange, dispatchErrors, pendingBuckets, cachedStates, cleanedThisRound[0]);
    }

    private int shardIndex(String key) {
        int shardCount = pendingBucketsByShard.size();
        if (shardCount <= 1) {
            return 0;
        }
        return Math.floorMod(key.hashCode(), shardCount);
    }

    private String buildKey(Long userId, String symbol, String side) {
        return userId + "|" + symbol + "|" + side;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    private static class MergeBucket {
        final Long userId;
        final String symbol;
        final String side;
        final PositionState state = new PositionState();
        final long firstUpdateAt;
        long lastUpdateAt;
        long lastEventTime;
        long lastSeq;
        int mergeCount;

        MergeBucket(Long userId, String symbol, String side, long now) {
            this.userId = userId;
            this.symbol = symbol;
            this.side = side;
            this.firstUpdateAt = now;
            this.lastUpdateAt = now;
            this.lastEventTime = now;
            this.lastSeq = now * 1000;
            this.mergeCount = 0;
            this.state.s = symbol;
            this.state.ps = side;
        }

        synchronized void merge(JSONObject update, long now) {
            state.apply(update);
            lastUpdateAt = now;
            if (update.containsKey("E")) {
                Long e = update.getLong("E");
                if (e != null && e > 0) {
                    lastEventTime = e;
                }
            } else {
                lastEventTime = now;
            }
            if (update.containsKey("seq")) {
                Long seq = update.getLong("seq");
                if (seq != null && seq > 0) {
                    lastSeq = seq;
                }
            }
            mergeCount++;
        }

        synchronized boolean isDue(long now, long mergeWindowMs, long maxDelayMs, int maxMergedEvents) {
            if (mergeCount >= maxMergedEvents) {
                return true;
            }
            if (now - firstUpdateAt >= maxDelayMs) {
                return true;
            }
            return now - lastUpdateAt >= mergeWindowMs;
        }

        synchronized boolean shouldFlushImmediately(int maxMergedEvents) {
            return mergeCount >= maxMergedEvents;
        }

        synchronized MergeSnapshot snapshot(long now) {
            return new MergeSnapshot(userId, state.copy(), mergeCount, lastEventTime, lastSeq, now);
        }
    }

    private static class MergeSnapshot {
        final Long userId;
        final PositionState latest;
        final int mergeCount;
        final long lastEventTime;
        final long lastSeq;
        final long flushAt;

        MergeSnapshot(Long userId, PositionState latest, int mergeCount, long lastEventTime, long lastSeq, long flushAt) {
            this.userId = userId;
            this.latest = latest;
            this.mergeCount = mergeCount;
            this.lastEventTime = lastEventTime;
            this.lastSeq = lastSeq;
            this.flushAt = flushAt;
        }
    }

    private static class LastDispatchedState {
        final PositionState state;
        final long dispatchedAt;

        LastDispatchedState(PositionState state, long dispatchedAt) {
            this.state = state;
            this.dispatchedAt = dispatchedAt;
        }
    }

    private static class PositionState {
        String s;
        String ps;
        String pa;
        String ep;
        String mp;
        String up;
        String mr;
        String lp;
        String mpi;
        String ipi;
        Integer l;
        String m;

        void apply(JSONObject update) {
            if (update == null) {
                return;
            }
            if (update.containsKey("s") && update.getString("s") != null) {
                this.s = update.getString("s");
            }
            if (update.containsKey("ps") && update.getString("ps") != null) {
                this.ps = update.getString("ps");
            }
            if (update.containsKey("pa") && update.getString("pa") != null) {
                this.pa = update.getString("pa");
            }
            if (update.containsKey("ep") && update.getString("ep") != null) {
                this.ep = update.getString("ep");
            }
            if (update.containsKey("mp") && update.getString("mp") != null) {
                this.mp = update.getString("mp");
            }
            if (update.containsKey("up") && update.getString("up") != null) {
                this.up = update.getString("up");
            }
            if (update.containsKey("mr") && update.getString("mr") != null) {
                this.mr = update.getString("mr");
            }
            if (update.containsKey("lp") && update.getString("lp") != null) {
                this.lp = update.getString("lp");
            }
            if (update.containsKey("mpi") && update.getString("mpi") != null) {
                this.mpi = update.getString("mpi");
            }
            if (update.containsKey("ipi") && update.getString("ipi") != null) {
                this.ipi = update.getString("ipi");
            }
            if (update.containsKey("l") && update.getInteger("l") != null) {
                this.l = update.getInteger("l");
            }
            if (update.containsKey("m") && update.getString("m") != null) {
                this.m = update.getString("m");
            }
        }

        PositionState copy() {
            PositionState copy = new PositionState();
            copy.s = this.s;
            copy.ps = this.ps;
            copy.pa = this.pa;
            copy.ep = this.ep;
            copy.mp = this.mp;
            copy.up = this.up;
            copy.mr = this.mr;
            copy.lp = this.lp;
            copy.mpi = this.mpi;
            copy.ipi = this.ipi;
            copy.l = this.l;
            copy.m = this.m;
            return copy;
        }

        JSONArray diffFields(PositionState previous) {
            JSONArray changed = new JSONArray();
            if (previous == null || !Objects.equals(previous.pa, this.pa)) {
                changed.add("pa");
            }
            if (previous == null || !Objects.equals(previous.ep, this.ep)) {
                changed.add("ep");
            }
            if (previous == null || !Objects.equals(previous.mp, this.mp)) {
                changed.add("mp");
            }
            if (previous == null || !Objects.equals(previous.up, this.up)) {
                changed.add("up");
            }
            if (previous == null || !Objects.equals(previous.mr, this.mr)) {
                changed.add("mr");
            }
            if (previous == null || !Objects.equals(previous.lp, this.lp)) {
                changed.add("lp");
            }
            if (previous == null || !Objects.equals(previous.mpi, this.mpi)) {
                changed.add("mpi");
            }
            if (previous == null || !Objects.equals(previous.ipi, this.ipi)) {
                changed.add("ipi");
            }
            if (previous == null || !Objects.equals(previous.l, this.l)) {
                changed.add("l");
            }
            if (previous == null || !Objects.equals(previous.m, this.m)) {
                changed.add("m");
            }
            return changed;
        }

        JSONObject toPayload() {
            JSONObject payload = new JSONObject();
            payload.put("e", "position");
            payload.put("s", s);
            payload.put("ps", ps);
            if (pa != null) {
                payload.put("pa", pa);
            }
            if (ep != null) {
                payload.put("ep", ep);
            }
            if (mp != null) {
                payload.put("mp", mp);
            }
            if (up != null) {
                payload.put("up", up);
            }
            if (mr != null) {
                payload.put("mr", mr);
            }
            if (lp != null) {
                payload.put("lp", lp);
            }
            if (mpi != null) {
                payload.put("mpi", mpi);
            }
            if (ipi != null) {
                payload.put("ipi", ipi);
            }
            if (l != null) {
                payload.put("l", l);
            }
            if (m != null) {
                payload.put("m", m);
            }
            return payload;
        }
    }
}
