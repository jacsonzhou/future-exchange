package com.exchange.liquidation.service.impl;

import com.exchange.liquidation.client.InsuranceFundClient;
import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.service.InsuranceFundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

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
        try {
            Map<String, Object> response = insuranceFundClient.expense(
                execution.getLiquidationId(), request);
            
            // 解析响应
            Boolean success = (Boolean) response.get("success");
            if (Boolean.TRUE.equals(success)) {
                Long actualCover = ((Number) response.get("coverAmount")).longValue();
                log.info("[InsuranceFundService] ✅ Insurance cover applied, " +
                        "liquidationId={}, coverAmount={}", 
                        execution.getLiquidationId(), actualCover);
                return actualCover;
            } else {
                String errorMsg = (String) response.get("errorMsg");
                log.error("[InsuranceFundService] ❌ Insurance cover failed, " +
                        "liquidationId={}, error={}", 
                        execution.getLiquidationId(), errorMsg);
                throw new RuntimeException("Insurance cover failed: " + errorMsg);
            }
        } catch (Exception e) {
            log.error("[InsuranceFundService] ❌ Exception when applying insurance cover, " +
                    "liquidationId={}", execution.getLiquidationId(), e);
            throw e;
        }
    }
    
    @Override
    public Long getInsuranceFundBalance(String symbol) {
        try {
            Map<String, Object> response = insuranceFundClient.getBalance(symbol);
            Boolean success = (Boolean) response.get("success");
            if (Boolean.TRUE.equals(success)) {
                return ((Number) response.get("balance")).longValue();
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

        try {
            Map<String, Object> response = insuranceFundClient.expense(bizSeq, request);

            // 解析响应
            Boolean success = (Boolean) response.get("success");
            if (Boolean.TRUE.equals(success)) {
                Long actualCover = ((Number) response.get("coverAmount")).longValue();
                log.info("[InsuranceFundService] ✅ Partial insurance cover applied, " +
                        "liquidationId={}, coverAmount={}, filledQty={}/{}",
                        execution.getLiquidationId(), actualCover, filledQty, totalQty);
                return actualCover;
            } else {
                String errorMsg = (String) response.get("errorMsg");
                log.error("[InsuranceFundService] ❌ Partial insurance cover failed, " +
                        "liquidationId={}, error={}",
                        execution.getLiquidationId(), errorMsg);
                throw new RuntimeException("Partial insurance cover failed: " + errorMsg);
            }
        } catch (Exception e) {
            log.error("[InsuranceFundService] ❌ Exception when applying partial insurance cover, " +
                    "liquidationId={}", execution.getLiquidationId(), e);
            throw e;
        }
    }
}

