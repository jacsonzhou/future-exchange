package com.exchange.marketmaker.controller;

import com.exchange.common.core.Result;
import com.exchange.marketmaker.dto.response.FeeLogResponse;
import com.exchange.marketmaker.dto.response.MmPerformanceResponse;
import com.exchange.marketmaker.service.MmFeeService;
import com.exchange.marketmaker.service.MmPerformanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 做市商考核Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mm")
public class MmPerformanceController {

    @Autowired
    private MmPerformanceService mmPerformanceService;

    @Autowired
    private MmFeeService mmFeeService;

    /**
     * 查询考核指标
     */
    @GetMapping("/performance")
    public Result<MmPerformanceResponse> getPerformance(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("symbol") String symbol,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {

        log.info("[MM-API] Get performance, userId={}, symbol={}, startDate={}, endDate={}",
                userId, symbol, startDate, endDate);

        MmPerformanceResponse response = mmPerformanceService.getPerformance(userId, symbol, startDate, endDate);

        return Result.success(response);
    }

    /**
     * 查询费率优惠记录
     */
    @GetMapping("/fee-log")
    public Result<FeeLogResponse> getFeeLog(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("startTime") String startTime,
            @RequestParam("endTime") String endTime) {

        log.info("[MM-API] Get fee log, userId={}, startTime={}, endTime={}",
                userId, startTime, endTime);

        FeeLogResponse response = mmFeeService.queryFeeLog(userId, startTime, endTime);

        return Result.success(response);
    }
}
