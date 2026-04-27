package com.exchange.market.service;

import com.exchange.market.job.BinanceKlineBackfillJob;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步K线回补任务服务。
 *
 * 目标：
 * 1. 任务异步执行，接口快速返回
 * 2. 断点续跑（start=max(open_time)+intervalMs）
 * 3. 任务状态可查询、可取消
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineBackfillAsyncTaskService {

    private final BinanceKlineBackfillJob backfillJob;
    private final KlineService klineService;

    @Autowired
    @Qualifier("marketDataTaskExecutor")
    private Executor taskExecutor;

    private final Map<String, TaskState> taskStore = new ConcurrentHashMap<>();
    private final Map<String, String> activeTaskByKey = new ConcurrentHashMap<>();

    public TaskSummary submitResumeTask(ResumeTaskRequest request) {
        ResumeTaskRequest req = sanitize(request);
        String taskKey = buildTaskKey(req.getSymbol(), req.getInterval());
        String activeTaskId = activeTaskByKey.get(taskKey);
        if (activeTaskId != null) {
            TaskState active = taskStore.get(activeTaskId);
            if (active != null && active.isRunning()) {
                return active.toSummary();
            }
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        TaskState state = new TaskState(
                taskId,
                taskKey,
                req.getSymbol(),
                req.getInterval(),
                "PENDING",
                now
        );

        taskStore.put(taskId, state);
        activeTaskByKey.put(taskKey, taskId);
        taskExecutor.execute(() -> runResumeTask(state, req));
        return state.toSummary();
    }

    public TaskSummary getTask(String taskId) {
        TaskState state = taskStore.get(taskId);
        return state == null ? null : state.toSummary();
    }

    public List<TaskSummary> listTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return taskStore.values().stream()
                .sorted(Comparator.comparingLong((TaskState state) -> state.createTime).reversed())
                .limit(safeLimit)
                .map(TaskState::toSummary)
                .toList();
    }

    public TaskSummary cancelTask(String taskId) {
        TaskState state = taskStore.get(taskId);
        if (state == null) {
            return null;
        }
        state.cancelRequested.set(true);
        return state.toSummary();
    }

    public boolean hasRunningTask(String symbol, String interval) {
        String key = buildTaskKey(symbol, interval);
        String taskId = activeTaskByKey.get(key);
        if (taskId == null) {
            return false;
        }
        TaskState state = taskStore.get(taskId);
        return state != null && state.isRunning();
    }

    public int countRunningTasks() {
        int count = 0;
        for (TaskState state : taskStore.values()) {
            if (state != null && state.isRunning()) {
                count++;
            }
        }
        return count;
    }

    private void runResumeTask(TaskState state, ResumeTaskRequest req) {
        long now = System.currentTimeMillis();
        state.status = "RUNNING";
        state.startTime = now;
        state.updateTime = now;

        long intervalMs = intervalToMillis(req.getInterval());
        if (intervalMs <= 0) {
            failTask(state, "unsupported interval: " + req.getInterval(), null);
            activeTaskByKey.remove(state.taskKey, state.taskId);
            return;
        }

        try {
            long endLimit = resolveEndLimit(req, intervalMs);
            Long latest = klineService.getLatestOpenTime(req.getSymbol(), req.getInterval());
            long start = latest == null
                    ? alignToInterval(System.currentTimeMillis() - req.getMaxCatchupDays() * 86_400_000L, intervalMs)
                    : latest + intervalMs;

            long hardStart = alignToInterval(System.currentTimeMillis() - req.getMaxCatchupDays() * 86_400_000L, intervalMs);
            if (start < hardStart) {
                start = hardStart;
            }

            int segLimit = Math.max(1, req.getMaxSegmentsPerTask());
            int segment = 0;
            while (start <= endLimit && segment < segLimit) {
                if (state.cancelRequested.get()) {
                    state.status = "CANCELLED";
                    state.finishTime = System.currentTimeMillis();
                    state.updateTime = state.finishTime;
                    state.lastSyncedOpenTime = klineService.getLatestOpenTime(req.getSymbol(), req.getInterval());
                    return;
                }

                segment++;
                long end = Math.min(endLimit, start + req.getChunkCandles() * intervalMs - intervalMs);
                state.currentStartTime = start;
                state.currentEndTime = end;
                state.totalSegments++;
                state.updateTime = System.currentTimeMillis();

                boolean success = false;
                String err = null;
                for (int attempt = 1; attempt <= req.getMaxRetriesPerSegment(); attempt++) {
                    try {
                        BinanceKlineBackfillJob.RangeBackfillResult result = backfillJob.backfillRangeManual(
                                req.getSymbol(),
                                req.getInterval(),
                                start,
                                end,
                                false
                        );
                        int imported = result.getImported();
                        state.successSegments++;
                        state.importedTotal += imported;
                        success = true;
                        break;
                    } catch (Exception e) {
                        err = e.getMessage();
                        if (attempt >= req.getMaxRetriesPerSegment()) {
                            break;
                        }
                        sleepSilently(1_000L * attempt);
                    }
                }

                if (!success) {
                    state.failedSegments++;
                    state.lastError = err == null ? "segment backfill failed" : err;
                    state.lastSyncedOpenTime = klineService.getLatestOpenTime(req.getSymbol(), req.getInterval());
                    state.updateTime = System.currentTimeMillis();
                    // 数据完整性优先：单段失败不推进cursor，下一轮从同一时间段继续。
                    break;
                }
                state.lastSyncedOpenTime = klineService.getLatestOpenTime(req.getSymbol(), req.getInterval());
                state.updateTime = System.currentTimeMillis();
                start = end + intervalMs;
            }

            if (req.isMissingRepairEnabled() && !state.cancelRequested.get()) {
                runMissingRepair(state, req, intervalMs);
            }

            if (state.cancelRequested.get()) {
                state.status = "CANCELLED";
            } else if (state.failedSegments > 0) {
                state.status = "PARTIAL_SUCCESS";
            } else {
                state.status = "COMPLETED";
            }
            state.finishTime = System.currentTimeMillis();
            state.updateTime = state.finishTime;
            state.lastSyncedOpenTime = klineService.getLatestOpenTime(req.getSymbol(), req.getInterval());
        } catch (Exception e) {
            failTask(state, "async backfill task failed", e);
        } finally {
            activeTaskByKey.remove(state.taskKey, state.taskId);
        }
    }

    private void runMissingRepair(TaskState state, ResumeTaskRequest req, long intervalMs) {
        try {
            long now = System.currentTimeMillis();
            long lastClosed = alignToInterval(now, intervalMs) - intervalMs;
            long lookback = Math.max(1, req.getMissingLookbackCandles()) * intervalMs;
            long start = Math.max(0L, lastClosed - lookback);
            BinanceKlineBackfillJob.MissingBackfillResult result = backfillJob.backfillMissingManual(
                    req.getSymbol(),
                    req.getInterval(),
                    start,
                    lastClosed,
                    req.getMissingMaxSegments()
            );
            state.missingDetectedSegments = result.getDetectedSegments();
            state.missingRepairedSegments = result.getRepairedSegments();
            state.importedTotal += result.getImported();
        } catch (Exception e) {
            state.lastError = "missing repair failed: " + e.getMessage();
            log.warn("[AsyncBackfill] missing repair failed, taskId={}, symbol={}, interval={}",
                    state.taskId, req.getSymbol(), req.getInterval(), e);
        }
    }

    private long resolveEndLimit(ResumeTaskRequest req, long intervalMs) {
        long now = System.currentTimeMillis();
        long lastClosedOpen = alignToInterval(now, intervalMs) - intervalMs;
        if (req.getEndTime() == null || req.getEndTime() <= 0) {
            return lastClosedOpen;
        }
        long requested = alignToInterval(req.getEndTime(), intervalMs);
        return Math.min(requested, lastClosedOpen);
    }

    private void failTask(TaskState state, String message, Exception e) {
        state.status = "FAILED";
        state.lastError = message + (e == null ? "" : (": " + e.getMessage()));
        state.finishTime = System.currentTimeMillis();
        state.updateTime = state.finishTime;
        log.error("[AsyncBackfill] {} taskId={}, symbol={}, interval={}",
                message, state.taskId, state.symbol, state.interval, e);
    }

    private ResumeTaskRequest sanitize(ResumeTaskRequest request) {
        ResumeTaskRequest req = request == null ? new ResumeTaskRequest() : request.copy();
        if (req.getSymbol() == null || req.getSymbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        req.setSymbol(req.getSymbol().trim().toUpperCase(Locale.ROOT));
        if (req.getInterval() == null || req.getInterval().isBlank()) {
            req.setInterval("1m");
        } else {
            req.setInterval(req.getInterval().trim());
        }
        req.setChunkCandles(clamp(req.getChunkCandles(), 60, 10_000, 1_440));
        req.setMaxCatchupDays(clamp(req.getMaxCatchupDays(), 1, 3_650, 365));
        req.setMaxSegmentsPerTask(clamp(req.getMaxSegmentsPerTask(), 1, 10_000, 512));
        req.setMaxRetriesPerSegment(clamp(req.getMaxRetriesPerSegment(), 1, 10, 3));
        req.setMissingLookbackCandles(clamp(req.getMissingLookbackCandles(), 60, 100_000, 4_320));
        req.setMissingMaxSegments(clamp(req.getMissingMaxSegments(), 1, 1_024, 64));
        return req;
    }

    private int clamp(Integer value, int min, int max, int def) {
        int raw = value == null ? def : value;
        return Math.max(min, Math.min(max, raw));
    }

    private String buildTaskKey(String symbol, String interval) {
        return normalize(symbol) + "|" + normalize(interval);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }

    private long alignToInterval(long timestamp, long intervalMs) {
        if (intervalMs <= 0) {
            return timestamp;
        }
        return (timestamp / intervalMs) * intervalMs;
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

    private void sleepSilently(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Data
    public static class ResumeTaskRequest {
        private String symbol;
        private String interval = "1m";
        private Integer chunkCandles = 1_440;
        private Integer maxCatchupDays = 365;
        private Integer maxSegmentsPerTask = 512;
        private Integer maxRetriesPerSegment = 3;
        private Long endTime;
        private boolean missingRepairEnabled = true;
        private Integer missingLookbackCandles = 4_320;
        private Integer missingMaxSegments = 64;

        public ResumeTaskRequest copy() {
            ResumeTaskRequest clone = new ResumeTaskRequest();
            clone.symbol = this.symbol;
            clone.interval = this.interval;
            clone.chunkCandles = this.chunkCandles;
            clone.maxCatchupDays = this.maxCatchupDays;
            clone.maxSegmentsPerTask = this.maxSegmentsPerTask;
            clone.maxRetriesPerSegment = this.maxRetriesPerSegment;
            clone.endTime = this.endTime;
            clone.missingRepairEnabled = this.missingRepairEnabled;
            clone.missingLookbackCandles = this.missingLookbackCandles;
            clone.missingMaxSegments = this.missingMaxSegments;
            return clone;
        }
    }

    @Data
    @Builder
    public static class TaskSummary {
        private String taskId;
        private String symbol;
        private String interval;
        private String status;
        private long createTime;
        private Long startTime;
        private Long finishTime;
        private Long updateTime;
        private Long currentStartTime;
        private Long currentEndTime;
        private Long lastSyncedOpenTime;
        private int totalSegments;
        private int successSegments;
        private int failedSegments;
        private int importedTotal;
        private int missingDetectedSegments;
        private int missingRepairedSegments;
        private String lastError;
        private boolean cancelRequested;
    }

    private static class TaskState {
        private final String taskId;
        private final String taskKey;
        private final String symbol;
        private final String interval;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile String status;
        private final long createTime;
        private volatile Long startTime;
        private volatile Long finishTime;
        private volatile Long updateTime;
        private volatile Long currentStartTime;
        private volatile Long currentEndTime;
        private volatile Long lastSyncedOpenTime;
        private volatile int totalSegments;
        private volatile int successSegments;
        private volatile int failedSegments;
        private volatile int importedTotal;
        private volatile int missingDetectedSegments;
        private volatile int missingRepairedSegments;
        private volatile String lastError;

        private TaskState(String taskId,
                          String taskKey,
                          String symbol,
                          String interval,
                          String status,
                          long createTime) {
            this.taskId = taskId;
            this.taskKey = taskKey;
            this.symbol = symbol;
            this.interval = interval;
            this.status = status;
            this.createTime = createTime;
            this.updateTime = createTime;
        }

        private boolean isRunning() {
            return "PENDING".equals(status) || "RUNNING".equals(status);
        }

        private TaskSummary toSummary() {
            return TaskSummary.builder()
                    .taskId(taskId)
                    .symbol(symbol)
                    .interval(interval)
                    .status(status)
                    .createTime(createTime)
                    .startTime(startTime)
                    .finishTime(finishTime)
                    .updateTime(updateTime)
                    .currentStartTime(currentStartTime)
                    .currentEndTime(currentEndTime)
                    .lastSyncedOpenTime(lastSyncedOpenTime)
                    .totalSegments(totalSegments)
                    .successSegments(successSegments)
                    .failedSegments(failedSegments)
                    .importedTotal(importedTotal)
                    .missingDetectedSegments(missingDetectedSegments)
                    .missingRepairedSegments(missingRepairedSegments)
                    .lastError(lastError)
                    .cancelRequested(cancelRequested.get())
                    .build();
        }
    }
}
