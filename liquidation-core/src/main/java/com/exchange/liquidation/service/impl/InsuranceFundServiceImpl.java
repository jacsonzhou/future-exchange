package com.exchange.liquidation.service.impl;

import com.exchange.liquidation.client.InsuranceFundClient;
import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.service.InsuranceFundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 保险基金服务实现
 * 
 * 处理保险基金赔付逻辑，支持重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsuranceFundServiceImpl implements InsuranceFundService {

    private static final String CHANGE_TYPE_LIQUIDATION_PROFIT_INJECTION = "LIQUIDATION_PROFIT_INJECTION";
    
    private final InsuranceFundClient insuranceFundClient;
    
    @Override
    @Retryable(
        // 只重试网络相关异常，不重试业务异常
        include = {java.io.IOException.class, java.util.concurrent.TimeoutException.class, org.springframework.web.client.ResourceAccessException.class},
        maxAttempts = 3,
        // 指数退避：1s, 2s, 4s (从100ms增加到1s)
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
    )
    public Long applyInsuranceCover(LiquidationExecution execution) {
        log.info("[InsuranceFundService] Applying insurance cover, liquidationId={}, " +
                "bankruptLoss={}", execution.getLiquidationId(), execution.getBankruptLoss());
        
        // 如果没有穿仓损失，不需要赔付
        if (execution.getBankruptLoss() == null || execution.getBankruptLoss() <= 0) {
            log.info("[InsuranceFundService] No bankrupt loss, skip insurance cover, " +
                    "liquidationId={}", execution.getLiquidationId());
            return 0L;
        }
        
        // 查询保险基金余额
        Long balance = getInsuranceFundBalance(execution.getSymbol());
        if (balance == null || balance <= 0) {
            log.warn("[InsuranceFundService] Insurance fund balance is zero or negative, " +
                    "liquidationId={}, balance={}", execution.getLiquidationId(), balance);
            return 0L;
        }
        
        // 计算赔付金额：min(穿仓损失, 保险基金余额)
        Long coverAmount = Math.min(execution.getBankruptLoss(), balance);
        
        // 构建赔付请求
        InsuranceFundClient.InsuranceFundExpenseRequest request = 
            new InsuranceFundClient.InsuranceFundExpenseRequest();
        request.setSymbol(execution.getSymbol());
        request.setAmount(coverAmount);
        request.setReason("Liquidation bankrupt loss: " + execution.getLiquidationId());
        request.setUserId(execution.getUserId());
        request.setPositionId(execution.getPositionId());
        
        // 调用保险基金服务（使用liquidationId作为bizSeq，保证幂等）
        Map<String, Object> response = insuranceFundClient.expense(
            execution.getLiquidationId(), request);

        // 解析响应：业务失败不阻断强平完成，按 0 赔付继续走 ADL 判定
        if (!isSuccess(response)) {
            log.warn("[InsuranceFundService] Insurance cover request rejected, liquidationId={}, error={}",
                    execution.getLiquidationId(), response == null ? null : response.get("errorMsg"));
            return 0L;
        }

        Long actualCover = numberToLong(response == null ? null : response.get("coverAmount"));
        log.info("[InsuranceFundService] ✅ Insurance cover applied, liquidationId={}, coverAmount={}",
                execution.getLiquidationId(), actualCover);
        return actualCover;
    }
    
    @Override
    public Long getInsuranceFundBalance(String symbol) {
        try {
            Map<String, Object> response = insuranceFundClient.getBalance(symbol);
            if (isSuccess(response)) {
                return numberToLong(response.get("balance"));
            } else {
                log.warn("[InsuranceFundService] Failed to get insurance fund balance, " +
                        "symbol={}", symbol);
                return 0L;
            }
        } catch (Exception e) {
            log.error("[InsuranceFundService] Exception when getting insurance fund balance, " +
                    "symbol={}", symbol, e);
            return 0L;
        }
    }

    @Override
    @Retryable(
        include = {java.io.IOException.class, java.util.concurrent.TimeoutException.class, org.springframework.web.client.ResourceAccessException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
    )
    public Long applyPartialInsuranceCover(LiquidationExecution execution,
                                            Long partialBankruptLoss,
                                            Long filledQty,
                                            Long totalQty) {
        log.info("[InsuranceFundService] Applying partial insurance cover, liquidationId={}, " +
                "partialBankruptLoss={}, filledQty={}, totalQty={}",
                execution.getLiquidationId(), partialBankruptLoss, filledQty, totalQty);

        // 如果没有穿仓损失，不需要赔付
        if (partialBankruptLoss == null || partialBankruptLoss <= 0) {
            log.info("[InsuranceFundService] No partial bankrupt loss, skip insurance cover, " +
                    "liquidationId={}", execution.getLiquidationId());
            return 0L;
        }

        // 查询保险基金余额
        Long balance = getInsuranceFundBalance(execution.getSymbol());
        if (balance == null || balance <= 0) {
            log.warn("[InsuranceFundService] Insurance fund balance is zero or negative, " +
                    "liquidationId={}, balance={}", execution.getLiquidationId(), balance);
            return 0L;
        }

        // 计算赔付金额：min(部分穿仓损失, 保险基金余额)
        Long coverAmount = Math.min(partialBankruptLoss, balance);

        // 构建赔付请求
        InsuranceFundClient.InsuranceFundExpenseRequest request =
            new InsuranceFundClient.InsuranceFundExpenseRequest();
        request.setSymbol(execution.getSymbol());
        request.setAmount(coverAmount);
        request.setReason(String.format("Partial liquidation bankrupt loss: %s (filled: %d/%d)",
                execution.getLiquidationId(), filledQty, totalQty));
        request.setUserId(execution.getUserId());
        request.setPositionId(execution.getPositionId());

        // 调用保险基金服务
        // 使用 liquidationId + filledQty 作为 bizSeq，保证幂等（支持同一强平多次部分成交）
        String bizSeq = execution.getLiquidationId() + "_partial_" + filledQty;

        Map<String, Object> response = insuranceFundClient.expense(bizSeq, request);
        if (!isSuccess(response)) {
            log.warn("[InsuranceFundService] Partial insurance cover rejected, liquidationId={}, error={}",
                    execution.getLiquidationId(), response == null ? null : response.get("errorMsg"));
            return 0L;
        }
        Long actualCover = numberToLong(response.get("coverAmount"));
        log.info("[InsuranceFundService] ✅ Partial insurance cover applied, " +
                "liquidationId={}, coverAmount={}, filledQty={}/{}",
                execution.getLiquidationId(), actualCover, filledQty, totalQty);
        return actualCover;
    }

    @Override
    @Retryable(
        include = {java.io.IOException.class, java.util.concurrent.TimeoutException.class, org.springframework.web.client.ResourceAccessException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000)
    )
    public Long injectLiquidationSurplus(LiquidationExecution execution) {
        if (execution == null) {
            return 0L;
        }

        Long bankruptLoss = execution.getBankruptLoss();
        if (bankruptLoss != null && bankruptLoss > 0) {
            // 穿仓场景优先处理赔付，不做盈余注资
            return 0L;
        }

        Long surplusAmount = calculateLiquidationSurplus(execution);
        if (surplusAmount <= 0) {
            log.info("[InsuranceFundService] No liquidation surplus to inject, liquidationId={}, " +
                            "realizedPnl={}, initialMargin={}",
                    execution.getLiquidationId(), execution.getRealizedPnl(), execution.getInitialMargin());
            return 0L;
        }

        InsuranceFundClient.InsuranceFundIncomeRequest request =
            new InsuranceFundClient.InsuranceFundIncomeRequest();
        request.setSymbol(execution.getSymbol());
        request.setAmount(surplusAmount);
        request.setReason("Liquidation surplus injection: " + execution.getLiquidationId());
        request.setChangeType(CHANGE_TYPE_LIQUIDATION_PROFIT_INJECTION);

        String bizSeq = execution.getLiquidationId() + "_income";
        Map<String, Object> response = insuranceFundClient.income(bizSeq, request);
        if (!isSuccess(response)) {
            log.warn("[InsuranceFundService] Liquidation surplus injection rejected, liquidationId={}, error={}",
                    execution.getLiquidationId(), response == null ? null : response.get("errorMsg"));
            return 0L;
        }

        Long incomeAmount = numberToLong(response.get("incomeAmount"));
        log.info("[InsuranceFundService] ✅ Liquidation surplus injected, liquidationId={}, incomeAmount={}",
                execution.getLiquidationId(), incomeAmount);
        return incomeAmount;
    }

    @Recover
    public Long recoverApplyInsuranceCover(Exception ex, LiquidationExecution execution) {
        log.error("[InsuranceFundService] Fallback applyInsuranceCover after retries, " +
                        "liquidationId={}, reason={}",
                execution == null ? "null" : execution.getLiquidationId(), ex.getMessage(), ex);
        return 0L;
    }

    @Recover
    public Long recoverApplyPartialInsuranceCover(
            Exception ex,
            LiquidationExecution execution,
            Long partialBankruptLoss,
            Long filledQty,
            Long totalQty
    ) {
        log.error("[InsuranceFundService] Fallback applyPartialInsuranceCover after retries, " +
                        "liquidationId={}, partialBankruptLoss={}, filledQty={}, totalQty={}, reason={}",
                execution == null ? "null" : execution.getLiquidationId(),
                partialBankruptLoss, filledQty, totalQty, ex.getMessage(), ex);
        return 0L;
    }

    @Recover
    public Long recoverInjectLiquidationSurplus(Exception ex, LiquidationExecution execution) {
        log.error("[InsuranceFundService] Fallback injectLiquidationSurplus after retries, " +
                        "liquidationId={}, reason={}",
                execution == null ? "null" : execution.getLiquidationId(), ex.getMessage(), ex);
        return 0L;
    }

    private boolean isSuccess(Map<String, Object> response) {
        if (response == null) {
            return false;
        }
        Object value = response.get("success");
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return false;
    }

    private Long numberToLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private Long calculateLiquidationSurplus(LiquidationExecution execution) {
        Long realizedPnl = execution.getRealizedPnl();
        Long initialMargin = execution.getInitialMargin();
        if (realizedPnl == null || initialMargin == null) {
            return 0L;
        }
        BigDecimal total = BigDecimal.valueOf(realizedPnl).add(BigDecimal.valueOf(initialMargin));
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }
        return total.longValue();
    }

}
