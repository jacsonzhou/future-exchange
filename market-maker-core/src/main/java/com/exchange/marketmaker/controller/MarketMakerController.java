package com.exchange.marketmaker.controller;

import com.exchange.common.core.Result;
import com.exchange.marketmaker.dto.request.ApplyMarketMakerRequest;
import com.exchange.marketmaker.dto.response.MmStatusResponse;
import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.enums.MmLevel;
import com.exchange.marketmaker.service.MarketMakerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 做市商管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mm")
public class MarketMakerController {

    @Autowired
    private MarketMakerService marketMakerService;

    /**
     * 申请做市商
     */
    @PostMapping("/apply")
    public Result<Long> applyMarketMaker(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody ApplyMarketMakerRequest request) {

        log.info("[MM-API] Apply market maker, userId={}, companyName={}",
                userId, request.getCompanyName());

        // TODO: 创建申请记录
        // 实际应该插入到 t_market_maker_application 表

        return Result.success(10001L);
    }

    /**
     * 查询做市商状态
     */
    @GetMapping("/status")
    public Result<MmStatusResponse> getStatus(
            @RequestHeader("X-User-Id") Long userId) {

        log.info("[MM-API] Get MM status, userId={}", userId);

        MarketMaker mm = marketMakerService.getMarketMaker(userId);

        MmStatusResponse response = new MmStatusResponse();
        if (mm != null && "ACTIVE".equals(mm.getStatus())) {
            response.setIsMarketMaker(true);
            response.setLevel(mm.getLevel());
            response.setStatus(mm.getStatus());
            response.setMakerFeeRate(formatFeeRate(mm.getMakerFeeRate()));
            response.setTakerFeeRate(formatFeeRate(mm.getTakerFeeRate()));
            response.setApiLimit(mm.getApiLimitPerSec());

            MmStatusResponse.EvalPeriod period = new MmStatusResponse.EvalPeriod();
            if (mm.getEvalPeriodStart() != null) {
                period.setStart(mm.getEvalPeriodStart().toString());
            }
            if (mm.getEvalPeriodEnd() != null) {
                period.setEnd(mm.getEvalPeriodEnd().toString());
            }
            response.setEvalPeriod(period);
        } else {
            response.setIsMarketMaker(false);
        }

        return Result.success(response);
    }

    /**
     * 退出做市商（内部接口）
     */
    @PostMapping("/withdraw")
    public Result<String> withdrawMarketMaker(
            @RequestHeader("X-User-Id") Long userId) {

        log.info("[MM-API] Withdraw market maker, userId={}", userId);

        // TODO: 实现退出逻辑
        // 1. 检查是否有未完成订单
        // 2. 检查是否有持仓
        // 3. 更新状态为IN_COOLING_PERIOD

        return Result.success("已进入7天冷静期");
    }

    private String formatFeeRate(Long feeRate) {
        return String.format("%.2f%%", feeRate / 1000000.0);
    }
}
