package com.exchange.adl.controller;

import com.exchange.adl.dto.AdlHistoryResponse;
import com.exchange.adl.dto.AdlRankingResponse;
import com.exchange.adl.dto.InsuranceFundResponse;
import com.exchange.adl.dto.UserAdlRecordResponse;
import com.exchange.adl.entity.AdlExecution;
import com.exchange.adl.entity.AdlRanking;
import com.exchange.adl.entity.InsuranceFund;
import com.exchange.adl.mapper.AdlExecutionMapper;
import com.exchange.adl.service.AdlService;
import com.exchange.adl.service.InsuranceFundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ADL Controller - ADL相关API接口
 *
 * 提供：
 * 1. ADL排名查询
 * 2. ADL历史查询
 * 3. 保险基金查询
 * 4. 用户ADL记录查询
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/adl")
public class AdlController {

    @Autowired
    private AdlService adlService;

    @Autowired
    private InsuranceFundService insuranceFundService;

    @Autowired
    private AdlExecutionMapper adlExecutionMapper;

    /**
     * 查询ADL排名
     *
     * GET /api/v1/adl/ranking?symbol=BTCUSDT&side=SHORT
     */
    @GetMapping("/ranking")
    public Map<String, Object> getAdlRanking(
            @RequestParam String symbol,
            @RequestParam String side,
            @RequestParam(required = false) Long userId) {

        try {
            // 获取ADL排名列表
            List<AdlRanking> rankings = adlService.getAdlRankings(symbol, side, 100);

            // 构造响应
            AdlRankingResponse response = new AdlRankingResponse();
            response.setSymbol(symbol);
            response.setSide(side);
            response.setUpdateTime(System.currentTimeMillis());

            // 转换排名列表
            List<AdlRankingResponse.RankingItem> items = rankings.stream()
                    .map(this::convertToRankingItem)
                    .collect(Collectors.toList());
            response.setRankings(items);

            // 如果提供了userId，查询用户排名
            if (userId != null) {
                AdlRanking userRanking = adlService.getUserAdlRank(userId, symbol);
                if (userRanking != null) {
                    response.setMyRank(userRanking.getAdlRank());
                    response.setAdlZone(userRanking.getAdlRank() <= 20); // 前20%为危险区
                    response.setRiskLevel(calculateRiskLevel(userRanking.getAdlRank()));
                }
            }

            return success(response);

        } catch (Exception e) {
            log.error("Failed to get ADL ranking", e);
            return error("查询ADL排名失败");
        }
    }

    /**
     * 查询ADL历史
     *
     * GET /api/v1/adl/history?symbol=BTCUSDT&limit=100
     */
    @GetMapping("/history")
    public Map<String, Object> getAdlHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "100") int limit) {

        try {
            // TODO: 从AdlExecutionMapper查询历史记录
            AdlHistoryResponse response = new AdlHistoryResponse();
            response.setItems(List.of());
            response.setTotal(0);

            return success(response);

        } catch (Exception e) {
            log.error("Failed to get ADL history", e);
            return error("查询ADL历史失败");
        }
    }

    /**
     * 查询保险基金余额
     *
     * GET /api/v1/adl/insurance-fund?symbol=BTCUSDT
     */
    @GetMapping("/insurance-fund")
    public Map<String, Object> getInsuranceFund(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "USDT") String currency) {

        try {
            InsuranceFund fund = insuranceFundService.getInsuranceFund(symbol, currency);

            if (fund == null) {
                return error("保险基金不存在");
            }

            // 构造响应
            InsuranceFundResponse response = new InsuranceFundResponse();
            response.setSymbol(fund.getSymbol());
            response.setCurrency(fund.getCurrency());
            response.setBalance(fund.getBalance());
            response.setAvailableBalance(fund.getAvailableBalance());
            response.setTotalIncome(fund.getTotalIncome());
            response.setTotalExpense(fund.getTotalExpense());
            response.setTodayIncome(fund.getTodayIncome());
            response.setTodayExpense(fund.getTodayExpense());
            response.setStatus(fund.getStatus());
            response.setUpdatedAt(fund.getUpdatedAt());

            return success(response);

        } catch (Exception e) {
            log.error("Failed to get insurance fund", e);
            return error("查询保险基金失败");
        }
    }

    /**
     * 查询用户ADL记录
     *
     * GET /api/v1/adl/user-records?startTime=xxx&endTime=xxx
     */
    @GetMapping("/user-records")
    public Map<String, Object> getUserAdlRecords(
            @RequestParam Long userId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(defaultValue = "100") int limit) {

        try {
            // TODO: 查询用户的ADL记录
            UserAdlRecordResponse response = new UserAdlRecordResponse();
            response.setItems(List.of());
            response.setTotal(0);

            return success(response);

        } catch (Exception e) {
            log.error("Failed to get user ADL records", e);
            return error("查询用户ADL记录失败");
        }
    }

    /**
     * 内部接口：获取ADL候选人
     *
     * POST /internal/adl/candidates
     */
    @PostMapping("/internal/candidates")
    public Map<String, Object> getAdlCandidates(@RequestBody Map<String, Object> request) {
        try {
            String symbol = (String) request.get("symbol");
            String oppositeSide = (String) request.get("oppositeSide");
            int limit = (int) request.getOrDefault("limit", 10);

            List<AdlRanking> candidates = adlService.getAdlRankings(symbol, oppositeSide, limit);

            return success(candidates);

        } catch (Exception e) {
            log.error("Failed to get ADL candidates", e);
            return error("获取ADL候选人失败");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 转换为排名项
     */
    private AdlRankingResponse.RankingItem convertToRankingItem(AdlRanking ranking) {
        AdlRankingResponse.RankingItem item = new AdlRankingResponse.RankingItem();
        item.setRank(ranking.getAdlRank());
        item.setUserId(maskUserId(ranking.getUserId())); // 脱敏
        item.setPositionId(ranking.getPositionId());
        item.setQty(toDecimal(ranking.getQty()));
        item.setPnlRatio(formatPercentage(toDecimal(ranking.getPnlRatio())));
        item.setEffectiveLeverage(toDecimal(ranking.getEffectiveLeverage()));
        item.setAdlScore(toDecimal(ranking.getAdlScore()));
        item.setRiskLevel(calculateRiskLevel(ranking.getAdlRank()));
        return item;
    }

    /**
     * 用户ID脱敏
     */
    private String maskUserId(Long userId) {
        if (userId == null) {
            return "***";
        }
        String str = userId.toString();
        if (str.length() <= 4) {
            return "***";
        }
        return str.substring(0, 2) + "***" + str.substring(str.length() - 2);
    }

    /**
     * 格式化百分比
     */
    private String formatPercentage(BigDecimal value) {
        if (value == null) {
            return "0%";
        }
        return value.multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private BigDecimal toDecimal(Number value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * 计算风险等级
     */
    private Integer calculateRiskLevel(Integer rank) {
        if (rank == null) {
            return 1;
        }
        if (rank <= 10) {
            return 5; // 最高风险
        } else if (rank <= 20) {
            return 4;
        } else if (rank <= 40) {
            return 3;
        } else if (rank <= 60) {
            return 2;
        } else {
            return 1; // 最低风险
        }
    }

    /**
     * 成功响应
     */
    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        result.put("message", "success");
        return result;
    }

    /**
     * 错误响应
     */
    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", -1);
        result.put("message", message);
        return result;
    }
}
