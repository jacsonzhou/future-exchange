package com.exchange.market.controller;

import com.exchange.common.core.ApiResponse;
import com.exchange.market.job.BinanceKlineBackfillJob;
import com.exchange.market.service.KlineBackfillAsyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * K线维护接口（手动重建/缺口修复）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/klines")
@RequiredArgsConstructor
public class KlineMaintenanceController {

    private final BinanceKlineBackfillJob backfillJob;
    private final KlineBackfillAsyncTaskService asyncTaskService;

    /**
     * 清空历史后重建（默认按1m回补，其他周期由查询聚合）。
     */
    @PostMapping("/rebuild-history")
    public ApiResponse<BinanceKlineBackfillJob.RebuildResult> rebuildHistory(
            @RequestBody(required = false) RebuildHistoryRequest request) {
        RebuildHistoryRequest req = request == null ? new RebuildHistoryRequest() : request;
        String interval = req.getInterval() == null || req.getInterval().isBlank() ? "1m" : req.getInterval().trim();
        int days = req.getDays() == null ? 365 : req.getDays();
        boolean truncateAll = req.getTruncateAll() == null || req.getTruncateAll();

        try {
            BinanceKlineBackfillJob.RebuildResult result = backfillJob.rebuildHistory(
                    truncateAll,
                    interval,
                    days,
                    req.getSymbols()
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[KlineMaintenance] rebuild-history failed, interval={}, days={}, truncateAll={}",
                    interval, days, truncateAll, e);
            return ApiResponse.error(500, "rebuild-history failed: " + e.getMessage());
        }
    }

    /**
     * 手动回补指定区间（可选先删除该区间）。
     */
    @PostMapping("/backfill-range")
    public ApiResponse<BinanceKlineBackfillJob.RangeBackfillResult> backfillRange(
            @RequestBody BackfillRangeRequest request) {
        if (request == null) {
            return ApiResponse.error(400, "request body is required");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            return ApiResponse.error(400, "symbol is required");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return ApiResponse.error(400, "startTime/endTime is required");
        }

        String interval = request.getInterval() == null || request.getInterval().isBlank()
                ? "1m"
                : request.getInterval().trim();
        boolean purgeRangeFirst = Boolean.TRUE.equals(request.getPurgeRangeFirst());

        try {
            BinanceKlineBackfillJob.RangeBackfillResult result = backfillJob.backfillRangeManual(
                    request.getSymbol(),
                    interval,
                    request.getStartTime(),
                    request.getEndTime(),
                    purgeRangeFirst
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[KlineMaintenance] backfill-range failed, symbol={}, interval={}, start={}, end={}, purgeRangeFirst={}",
                    request.getSymbol(), interval, request.getStartTime(), request.getEndTime(), purgeRangeFirst, e);
            return ApiResponse.error(500, "backfill-range failed: " + e.getMessage());
        }
    }

    /**
     * 扫描并修复区间缺口（只补缺失段）。
     */
    @PostMapping("/backfill-missing")
    public ApiResponse<BinanceKlineBackfillJob.MissingBackfillResult> backfillMissing(
            @RequestBody BackfillMissingRequest request) {
        if (request == null) {
            return ApiResponse.error(400, "request body is required");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            return ApiResponse.error(400, "symbol is required");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return ApiResponse.error(400, "startTime/endTime is required");
        }

        String interval = request.getInterval() == null || request.getInterval().isBlank()
                ? "1m"
                : request.getInterval().trim();
        int maxSegments = request.getMaxSegments() == null ? 64 : request.getMaxSegments();

        try {
            BinanceKlineBackfillJob.MissingBackfillResult result = backfillJob.backfillMissingManual(
                    request.getSymbol(),
                    interval,
                    request.getStartTime(),
                    request.getEndTime(),
                    maxSegments
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[KlineMaintenance] backfill-missing failed, symbol={}, interval={}, start={}, end={}, maxSegments={}",
                    request.getSymbol(), interval, request.getStartTime(), request.getEndTime(), maxSegments, e);
            return ApiResponse.error(500, "backfill-missing failed: " + e.getMessage());
        }
    }

    /**
     * 异步续跑回补（单币种）：start=max(open_time)+intervalMs 到 最新已收线。
     */
    @PostMapping("/backfill-resume-async")
    public ApiResponse<KlineBackfillAsyncTaskService.TaskSummary> backfillResumeAsync(
            @RequestBody BackfillResumeAsyncRequest request) {
        if (request == null) {
            return ApiResponse.error(400, "request body is required");
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            return ApiResponse.error(400, "symbol is required");
        }

        try {
            KlineBackfillAsyncTaskService.ResumeTaskRequest req = new KlineBackfillAsyncTaskService.ResumeTaskRequest();
            req.setSymbol(request.getSymbol());
            req.setInterval(request.getInterval());
            req.setChunkCandles(request.getChunkCandles());
            req.setMaxCatchupDays(request.getMaxCatchupDays());
            req.setMaxSegmentsPerTask(request.getMaxSegmentsPerTask());
            req.setMaxRetriesPerSegment(request.getMaxRetriesPerSegment());
            req.setEndTime(request.getEndTime());
            req.setMissingRepairEnabled(request.getMissingRepairEnabled() == null || request.getMissingRepairEnabled());
            req.setMissingLookbackCandles(request.getMissingLookbackCandles());
            req.setMissingMaxSegments(request.getMissingMaxSegments());
            KlineBackfillAsyncTaskService.TaskSummary task = asyncTaskService.submitResumeTask(req);
            return ApiResponse.success(task);
        } catch (Exception e) {
            log.error("[KlineMaintenance] backfill-resume-async failed, symbol={}", request.getSymbol(), e);
            return ApiResponse.error(500, "backfill-resume-async failed: " + e.getMessage());
        }
    }

    /**
     * 查询单个异步任务状态。
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<KlineBackfillAsyncTaskService.TaskSummary> getTask(@PathVariable("taskId") String taskId) {
        KlineBackfillAsyncTaskService.TaskSummary task = asyncTaskService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error(404, "task not found: " + taskId);
        }
        return ApiResponse.success(task);
    }

    /**
     * 查询最近任务列表。
     */
    @GetMapping("/tasks")
    public ApiResponse<List<KlineBackfillAsyncTaskService.TaskSummary>> listTasks(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int safeLimit = limit == null ? 20 : limit;
        return ApiResponse.success(asyncTaskService.listTasks(safeLimit));
    }

    /**
     * 取消异步任务。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<KlineBackfillAsyncTaskService.TaskSummary> cancelTask(@PathVariable("taskId") String taskId) {
        KlineBackfillAsyncTaskService.TaskSummary task = asyncTaskService.cancelTask(taskId);
        if (task == null) {
            return ApiResponse.error(404, "task not found: " + taskId);
        }
        return ApiResponse.success(task);
    }

    @lombok.Data
    public static class RebuildHistoryRequest {
        /**
         * 建议固定 1m，其他周期由聚合查询计算。
         */
        private String interval = "1m";
        /**
         * 回补天数。
         */
        private Integer days = 365;
        /**
         * 是否先清空kline_data + kline_realtime。
         */
        private Boolean truncateAll = true;
        /**
         * 指定交易对，空则走配置symbols。
         */
        private List<String> symbols;
    }

    @lombok.Data
    public static class BackfillRangeRequest {
        private String symbol;
        private String interval = "1m";
        private Long startTime;
        private Long endTime;
        /**
         * true：先删除该时间段再回补。
         */
        private Boolean purgeRangeFirst = false;
    }

    @lombok.Data
    public static class BackfillMissingRequest {
        private String symbol;
        private String interval = "1m";
        private Long startTime;
        private Long endTime;
        /**
         * 单次最多修复缺口段数量，防止一次请求过重。
         */
        private Integer maxSegments = 64;
    }

    @lombok.Data
    public static class BackfillResumeAsyncRequest {
        private String symbol;
        private String interval = "1m";
        /**
         * 每段回补多少根K线（1m下1440=1天）。
         */
        private Integer chunkCandles = 1_440;
        /**
         * 最多回补天数（防止无限回补）。
         */
        private Integer maxCatchupDays = 365;
        /**
         * 单任务最多执行分段数（避免任务过长不可控）。
         */
        private Integer maxSegmentsPerTask = 512;
        /**
         * 单分段失败重试次数。
         */
        private Integer maxRetriesPerSegment = 3;
        /**
         * 可选：限制本次任务的终点时间。
         */
        private Long endTime;
        /**
         * 任务结束后是否自动执行缺口修复。
         */
        private Boolean missingRepairEnabled = true;
        /**
         * 缺口扫描回看根数。
         */
        private Integer missingLookbackCandles = 4_320;
        /**
         * 单次最多修复缺口段数量。
         */
        private Integer missingMaxSegments = 64;
    }
}
