package com.exchange.adl.controller;

import com.exchange.adl.entity.InsuranceFund;
import com.exchange.adl.entity.InsuranceFundLog;
import com.exchange.adl.mapper.InsuranceFundLogMapper;
import com.exchange.adl.mapper.InsuranceFundMapper;
import com.exchange.adl.service.InsuranceFundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Insurance Fund Internal Controller
 *
 * 为 liquidation-core 提供保险基金内部契约：
 * 1. 查询余额
 * 2. 赔付支出（支持 bizSeq 幂等）
 * 3. 注资收入（支持 bizSeq 幂等，便于回归初始化）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/insurance-fund")
public class InsuranceFundInternalController {

    private static final BigDecimal SCALE = new BigDecimal("100000000");
    private static final String DEFAULT_CURRENCY = "USDT";
    private static final String TYPE_COVER_BANKRUPT = "COVER_BANKRUPT";
    private static final String TYPE_PLATFORM_SUBSIDY = "PLATFORM_SUBSIDY";

    private final InsuranceFundService insuranceFundService;
    private final InsuranceFundMapper insuranceFundMapper;
    private final InsuranceFundLogMapper insuranceFundLogMapper;

    @PostMapping("/balance")
    public Map<String, Object> balance(@RequestParam(required = false) String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);

        if (normalizedSymbol == null) {
            BigDecimal total = insuranceFundMapper.sumBalanceByCurrency(DEFAULT_CURRENCY);
            return ok(Map.of(
                    "symbol", "ALL",
                    "currency", DEFAULT_CURRENCY,
                    "balance", toRawLong(total)
            ));
        }

        ensureFundExists(normalizedSymbol);
        BigDecimal balance = insuranceFundService.getBalance(normalizedSymbol, DEFAULT_CURRENCY);
        return ok(Map.of(
                "symbol", normalizedSymbol,
                "currency", DEFAULT_CURRENCY,
                "balance", toRawLong(balance)
        ));
    }

    @PostMapping("/expense")
    public Map<String, Object> expense(
            @RequestParam("bizSeq") String bizSeq,
            @RequestBody InsuranceFundExpenseRequest request
    ) {
        if (bizSeq == null || bizSeq.isBlank()) {
            return fail("bizSeq is required");
        }
        if (request == null || request.getAmount() == null || request.getAmount() <= 0) {
            return fail("amount must be > 0");
        }
        String symbol = normalizeSymbol(request.getSymbol());
        if (symbol == null) {
            return fail("symbol is required");
        }

        InsuranceFundLog existed = findLogByBizSeq(bizSeq, TYPE_COVER_BANKRUPT, symbol);
        if (existed != null) {
            return ok(Map.of(
                    "symbol", symbol,
                    "currency", DEFAULT_CURRENCY,
                    "coverAmount", toRawLong(existed.getAmount().abs()),
                    "balance", toRawLong(insuranceFundService.getBalance(symbol, DEFAULT_CURRENCY)),
                    "idempotent", true
            ));
        }

        ensureFundExists(symbol);
        BigDecimal requested = fromRawLong(request.getAmount());
        BigDecimal available = insuranceFundService.getAvailableBalance(symbol, DEFAULT_CURRENCY);
        BigDecimal cover = available.min(requested);

        if (cover.compareTo(BigDecimal.ZERO) <= 0) {
            return ok(Map.of(
                    "symbol", symbol,
                    "currency", DEFAULT_CURRENCY,
                    "coverAmount", 0L,
                    "balance", toRawLong(insuranceFundService.getBalance(symbol, DEFAULT_CURRENCY)),
                    "message", "insufficient insurance fund"
            ));
        }

        boolean success = insuranceFundService.expense(
                symbol,
                DEFAULT_CURRENCY,
                cover,
                TYPE_COVER_BANKRUPT,
                bizSeq,
                request.getReason() == null ? "liquidation insurance cover" : request.getReason()
        );
        if (!success) {
            return fail("expense failed");
        }

        BigDecimal balanceAfter = insuranceFundService.getBalance(symbol, DEFAULT_CURRENCY);
        return ok(Map.of(
                "symbol", symbol,
                "currency", DEFAULT_CURRENCY,
                "coverAmount", toRawLong(cover),
                "balance", toRawLong(balanceAfter),
                "idempotent", false
        ));
    }

    @PostMapping("/income")
    public Map<String, Object> income(
            @RequestParam("bizSeq") String bizSeq,
            @RequestBody InsuranceFundIncomeRequest request
    ) {
        if (bizSeq == null || bizSeq.isBlank()) {
            return fail("bizSeq is required");
        }
        if (request == null || request.getAmount() == null || request.getAmount() <= 0) {
            return fail("amount must be > 0");
        }
        String symbol = normalizeSymbol(request.getSymbol());
        if (symbol == null) {
            return fail("symbol is required");
        }

        String changeType = normalizeChangeType(request.getChangeType(), TYPE_PLATFORM_SUBSIDY);
        InsuranceFundLog existed = findLogByBizSeq(bizSeq, changeType, symbol);
        if (existed != null) {
            return ok(Map.of(
                    "symbol", symbol,
                    "currency", DEFAULT_CURRENCY,
                    "incomeAmount", toRawLong(existed.getAmount()),
                    "balance", toRawLong(insuranceFundService.getBalance(symbol, DEFAULT_CURRENCY)),
                    "idempotent", true
            ));
        }

        ensureFundExists(symbol);
        BigDecimal incomeAmount = fromRawLong(request.getAmount());
        boolean success = insuranceFundService.income(
                symbol,
                DEFAULT_CURRENCY,
                incomeAmount,
                changeType,
                bizSeq,
                request.getReason() == null ? "insurance fund income" : request.getReason()
        );
        if (!success) {
            return fail("income failed");
        }

        BigDecimal balanceAfter = insuranceFundService.getBalance(symbol, DEFAULT_CURRENCY);
        return ok(Map.of(
                "symbol", symbol,
                "currency", DEFAULT_CURRENCY,
                "incomeAmount", toRawLong(incomeAmount),
                "balance", toRawLong(balanceAfter),
                "idempotent", false
        ));
    }

    private InsuranceFundLog findLogByBizSeq(String bizSeq, String changeType, String symbol) {
        List<InsuranceFundLog> logs = insuranceFundLogMapper.selectByRefId(bizSeq);
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        for (InsuranceFundLog log : logs) {
            if (log == null) {
                continue;
            }
            if (!changeType.equalsIgnoreCase(nullToEmpty(log.getChangeType()))) {
                continue;
            }
            if (!symbol.equalsIgnoreCase(nullToEmpty(log.getSymbol()))) {
                continue;
            }
            return log;
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void ensureFundExists(String symbol) {
        InsuranceFund fund = insuranceFundService.getInsuranceFund(symbol, DEFAULT_CURRENCY);
        if (fund != null) {
            return;
        }
        insuranceFundService.initInsuranceFund(symbol, DEFAULT_CURRENCY, BigDecimal.ZERO);
        log.info("[InsuranceFundInternal] Initialized missing fund, symbol={}, currency={}",
                symbol, DEFAULT_CURRENCY);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase();
    }

    private String normalizeChangeType(String changeType, String defaultValue) {
        if (changeType == null || changeType.isBlank()) {
            return defaultValue;
        }
        return changeType.trim().toUpperCase();
    }

    private BigDecimal fromRawLong(Long raw) {
        return BigDecimal.valueOf(raw).divide(SCALE, 8, RoundingMode.HALF_UP);
    }

    private Long toRawLong(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private Map<String, Object> ok(Map<String, Object> payload) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("code", 0);
        result.putAll(payload);
        return result;
    }

    private Map<String, Object> fail(String errorMsg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", -1);
        result.put("errorMsg", errorMsg);
        return result;
    }

    @Data
    public static class InsuranceFundExpenseRequest {
        private String symbol;
        private Long amount;
        private String reason;
        private Long userId;
        private Long positionId;
    }

    @Data
    public static class InsuranceFundIncomeRequest {
        private String symbol;
        private Long amount;
        private String reason;
        private String changeType;
    }
}
