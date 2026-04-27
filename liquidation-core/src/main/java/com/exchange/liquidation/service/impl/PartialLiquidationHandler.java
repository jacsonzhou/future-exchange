package com.exchange.liquidation.service.impl;

import com.exchange.liquidation.dto.CreateOrderRequest;
import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.mapper.LiquidationExecutionMapper;
import com.exchange.liquidation.service.InsuranceFundService;
import com.exchange.liquidation.service.PnLCalculatorService;
import com.exchange.liquidation.client.OmsClient;
import com.exchange.liquidation.client.PositionClient;
import com.exchange.liquidation.util.OmsResponseUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部分成交处理器
 * 
 * 处理强平订单部分成交的场景：
 * 1. 计算已成交部分的盈亏
 * 2. 计算已成交部分的穿仓损失
 * 3. 按比例申请保险基金赔付
 * 4. 创建剩余仓位强平订单
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartialLiquidationHandler {
    
    private final LiquidationExecutionMapper executionMapper;
    private final PnLCalculatorService pnLCalculatorService;
    private final InsuranceFundService insuranceFundService;
    private final OmsClient omsClient;
    
    @Value("${liquidation.order.min-split-qty:1000000}")
    private Long minSplitQty; // 最小拆分数量（8位小数，0.01 BTC）
    
    /**
     * 处理部分成交
     * 
     * @param execution 强平执行记录
     * @param filledQty 本次成交数量
     * @param avgPrice 平均成交价格
     */
    @Transactional(rollbackFor = Exception.class)
    public void handlePartialFilled(LiquidationExecution execution, Long filledQty, Long avgPrice) {
        log.info("🔄 [PartialLiquidationHandler] Handling partial fill, " +
                "liquidationId={}, filledQty={}, avgPrice={}, totalQty={}",
                execution.getLiquidationId(), filledQty, avgPrice, execution.getQuantity());

        Long effectiveAvgPrice = resolveExecutedPrice(execution, avgPrice);
        if (effectiveAvgPrice == null || effectiveAvgPrice <= 0) {
            throw new IllegalStateException(
                    "Partial fill missing valid execution price, liquidationId=" + execution.getLiquidationId());
        }

        // 1. 计算已成交部分的盈亏
        Long partialPnl = calculatePartialPnL(execution, filledQty, effectiveAvgPrice);
        
        // 2. 累加部分成交盈亏
        Long currentPartialPnl = execution.getPartialPnl() != null ? execution.getPartialPnl() : 0L;
        Long newPartialPnl = currentPartialPnl + partialPnl;
        execution.setPartialPnl(newPartialPnl);
        
        // 3. 计算已成交部分的穿仓损失
        Long partialBankruptLoss = calculatePartialBankruptLoss(execution, partialPnl, filledQty);
        
        // 4. 累加部分成交穿仓损失
        Long currentPartialBankruptLoss = execution.getPartialBankruptLoss() != null 
            ? execution.getPartialBankruptLoss() : 0L;
        Long newPartialBankruptLoss = currentPartialBankruptLoss + partialBankruptLoss;
        execution.setPartialBankruptLoss(newPartialBankruptLoss);
        
        // 5. 如果已穿仓，立即申请保险基金赔付（按比例）
        if (partialBankruptLoss > 0) {
            Long partialInsuranceCover = applyPartialInsuranceCover(
                execution, partialBankruptLoss, filledQty, execution.getQuantity());
            
            // 累加保险基金赔付
            Long currentInsuranceCover = execution.getInsuranceCover() != null 
                ? execution.getInsuranceCover() : 0L;
            execution.setInsuranceCover(currentInsuranceCover + partialInsuranceCover);
        }
        
        // 6. 计算剩余仓位
        Long remainingQty = execution.getQuantity() - execution.getExecutedQty();
        execution.setRemainingQty(remainingQty);
        
        // 7. 更新执行记录
        execution.setUpdatedAt(System.currentTimeMillis());
        executionMapper.updateById(execution);
        
        log.info("✅ [PartialLiquidationHandler] Partial fill processed, " +
                "liquidationId={}, partialPnl={}, partialBankruptLoss={}, remainingQty={}",
                execution.getLiquidationId(), partialPnl, partialBankruptLoss, remainingQty);
        
        // 8. 创建剩余仓位强平订单（如果剩余数量大于最小拆分单位）
        if (remainingQty != null && remainingQty > minSplitQty) {
            createRemainingLiquidationOrder(execution, remainingQty);
        } else if (remainingQty != null && remainingQty > 0) {
            log.warn("⚠️ [PartialLiquidationHandler] Remaining qty too small, " +
                    "liquidationId={}, remainingQty={}, minSplitQty={}",
                    execution.getLiquidationId(), remainingQty, minSplitQty);
            // 剩余数量太小，无法继续拆分，标记为完成
            execution.setStatus("COMPLETED");
            execution.setCompletedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
        }
    }

    private Long resolveExecutedPrice(LiquidationExecution execution, Long avgPrice) {
        if (avgPrice != null && avgPrice > 0) {
            return avgPrice;
        }

        Long fallback = execution.getExecutedPrice();
        String source = "executedPrice";
        if (fallback == null || fallback <= 0) {
            fallback = execution.getMarkPrice();
            source = "markPrice";
        }
        if (fallback == null || fallback <= 0) {
            fallback = execution.getTriggerPrice();
            source = "triggerPrice";
        }

        if (fallback != null && fallback > 0) {
            log.warn("⚠️ [PartialLiquidationHandler] avgPrice missing, fallback to {}={}, liquidationId={}",
                    source, fallback, execution.getLiquidationId());
        } else {
            log.error("❌ [PartialLiquidationHandler] avgPrice and fallback price missing, liquidationId={}",
                    execution.getLiquidationId());
        }
        return fallback;
    }
    
    /**
     * 计算部分成交盈亏
     * 
     * @param execution 强平执行记录
     * @param filledQty 已成交数量
     * @param avgPrice 平均成交价格（已做兜底）
     * @return 部分成交盈亏（8位小数）
     */
    private Long calculatePartialPnL(LiquidationExecution execution, Long filledQty, Long avgPrice) {
        // 方向：1=多仓(+1)，2=空仓(-1)
        int direction = execution.getPositionSide() == 1 ? 1 : -1;
        
        // 使用BigDecimal避免溢出
        java.math.BigDecimal executedPrice = java.math.BigDecimal.valueOf(avgPrice);
        java.math.BigDecimal entryPrice = java.math.BigDecimal.valueOf(execution.getEntryPrice());
        java.math.BigDecimal priceDiff = executedPrice.subtract(entryPrice);
        
        java.math.BigDecimal qty = java.math.BigDecimal.valueOf(filledQty);
        java.math.BigDecimal directionBd = java.math.BigDecimal.valueOf(direction);
        java.math.BigDecimal divisor = java.math.BigDecimal.valueOf(100_000_000L);
        
        // 盈亏 = (价格差 × 方向 × 数量) / 100,000,000
        java.math.BigDecimal partialPnl = priceDiff
                .multiply(directionBd)
                .multiply(qty)
                .divide(divisor, 0, java.math.RoundingMode.DOWN);
        
        Long result = partialPnl.longValue();
        
        log.debug("💰 [PartialLiquidationHandler] Partial PnL calculated, " +
                "liquidationId={}, filledQty={}, avgPrice={}, entryPrice={}, " +
                "direction={}, partialPnl={}",
                execution.getLiquidationId(), filledQty, avgPrice, execution.getEntryPrice(),
                direction, result);
        
        return result;
    }
    
    /**
     * 计算部分成交穿仓损失
     * 
     * @param execution 强平执行记录
     * @param partialPnl 部分成交盈亏
     * @param filledQty 已成交数量
     * @return 部分成交穿仓损失（8位小数）
     */
    private Long calculatePartialBankruptLoss(LiquidationExecution execution, 
                                               Long partialPnl, Long filledQty) {
        if (execution.getInitialMargin() == null) {
            return 0L;
        }
        
        // 计算已成交部分对应的初始保证金（按比例）
        java.math.BigDecimal totalQty = java.math.BigDecimal.valueOf(execution.getQuantity());
        java.math.BigDecimal filledQtyBd = java.math.BigDecimal.valueOf(filledQty);
        java.math.BigDecimal initialMarginBd = java.math.BigDecimal.valueOf(execution.getInitialMargin());
        
        // 已成交部分保证金 = 初始保证金 × (已成交数量 / 总数量)
        java.math.BigDecimal partialMargin = initialMarginBd
                .multiply(filledQtyBd)
                .divide(totalQty, 0, java.math.RoundingMode.DOWN);
        
        // 穿仓损失 = min(部分盈亏 + 部分保证金, 0) 的绝对值
        java.math.BigDecimal partialPnlBd = java.math.BigDecimal.valueOf(partialPnl);
        java.math.BigDecimal total = partialPnlBd.add(partialMargin);
        
        if (total.compareTo(java.math.BigDecimal.ZERO) < 0) {
            // 穿仓，返回损失金额（正数）
            return total.negate().longValue();
        } else {
            // 未穿仓
            return 0L;
        }
    }
    
    /**
     * 按比例申请保险基金赔付
     * 
     * @param execution 强平执行记录
     * @param partialBankruptLoss 部分成交穿仓损失
     * @param filledQty 已成交数量
     * @param totalQty 总数量
     * @return 赔付金额（8位小数）
     */
    private Long applyPartialInsuranceCover(LiquidationExecution execution,
                                           Long partialBankruptLoss,
                                           Long filledQty,
                                           Long totalQty) {
        log.info("💳 [PartialLiquidationHandler] Applying partial insurance cover, " +
                "liquidationId={}, partialBankruptLoss={}, filledQty={}, totalQty={}",
                execution.getLiquidationId(), partialBankruptLoss, filledQty, totalQty);
        
        // 创建临时执行记录用于保险基金服务
        LiquidationExecution tempExecution = new LiquidationExecution();
        tempExecution.setLiquidationId(execution.getLiquidationId());
        tempExecution.setUserId(execution.getUserId());
        tempExecution.setPositionId(execution.getPositionId());
        tempExecution.setSymbol(execution.getSymbol());
        tempExecution.setBankruptLoss(partialBankruptLoss);
        
        // 调用保险基金服务
        Long insuranceCover = insuranceFundService.applyInsuranceCover(tempExecution);
        
        log.info("✅ [PartialLiquidationHandler] Partial insurance cover applied, " +
                "liquidationId={}, partialBankruptLoss={}, insuranceCover={}",
                execution.getLiquidationId(), partialBankruptLoss, insuranceCover);
        
        return insuranceCover;
    }
    
    /**
     * 创建剩余仓位强平订单
     * 
     * @param execution 原始强平执行记录
     * @param remainingQty 剩余数量
     */
    private void createRemainingLiquidationOrder(LiquidationExecution execution, Long remainingQty) {
        log.info("📝 [PartialLiquidationHandler] Creating remaining liquidation order, " +
                "liquidationId={}, remainingQty={}",
                execution.getLiquidationId(), remainingQty);
        
        try {
            // 1. 查询最新标记价格（用于价格保护）
            Long latestMarkPrice = getLatestMarkPrice(execution);
            
            // 2. 判断订单类型（价格保护）
            String orderType = determineOrderType(execution.getExecutedPrice(), latestMarkPrice);
            Long limitPrice = null;
            if ("LIMIT".equals(orderType)) {
                // 限价单价格 = 标记价格 ± 1%（根据方向）
                int direction = execution.getPositionSide() == 1 ? -1 : 1; // 多仓卖单向下，空仓买单向上
                long priceOffset = latestMarkPrice * 1 / 100; // 1%
                limitPrice = latestMarkPrice + (direction * priceOffset);
            }
            
            // 3. 构建新的强平订单请求
            CreateOrderRequest req = new CreateOrderRequest();
            req.setUserId(execution.getUserId());
            req.setSymbol(execution.getSymbol());
            req.setSide(execution.getSide());
            req.setOrderType(orderType);
            req.setQuantity(remainingQty);
            req.setPrice(limitPrice);
            req.setReduceOnly(true);
            req.setOrderSource("LIQUIDATION");
            req.setPositionId(execution.getPositionId());
            req.setLiquidationId(execution.getLiquidationId());
            
            // 4. 创建订单
            Object raw = omsClient.createOrder(req);
            Long newOrderId = OmsResponseUtil.extractOrderId(raw);
            if (newOrderId == null) {
                throw new IllegalStateException(
                    "OMS create remaining liquidation order failed, liquidationId="
                        + execution.getLiquidationId() + ", "
                        + OmsResponseUtil.extractErrorMessage(raw)
                );
            }
            
            // 5. 更新原始强平记录，关联新订单
            execution.setRemainingOrderId(newOrderId);
            execution.setUpdatedAt(System.currentTimeMillis());
            executionMapper.updateById(execution);
            
            // 6. 创建新的强平执行记录（用于追踪剩余仓位）
            LiquidationExecution remainingExecution = new LiquidationExecution();
            remainingExecution.setLiquidationId(generateRemainingLiquidationId(execution.getLiquidationId()));
            remainingExecution.setUserId(execution.getUserId());
            remainingExecution.setPositionId(execution.getPositionId());
            remainingExecution.setSymbol(execution.getSymbol());
            remainingExecution.setMarginMode(execution.getMarginMode());
            remainingExecution.setPositionSide(execution.getPositionSide());
            remainingExecution.setTriggerType("REMAINING_LIQUIDATION");
            remainingExecution.setTriggerPrice(latestMarkPrice);
            remainingExecution.setOrderId(newOrderId);
            remainingExecution.setOrderType(orderType);
            remainingExecution.setSide(execution.getSide());
            remainingExecution.setQuantity(remainingQty);
            remainingExecution.setEntryPrice(execution.getEntryPrice());
            remainingExecution.setBankruptcyPrice(execution.getBankruptcyPrice());
            remainingExecution.setMarkPrice(latestMarkPrice);
            remainingExecution.setInitialMargin(execution.getInitialMargin());
            remainingExecution.setMaintenanceMargin(execution.getMaintenanceMargin());
            remainingExecution.setStatus("SUBMITTED");
            remainingExecution.setParentLiquidationId(execution.getLiquidationId());
            remainingExecution.setTriggeredAt(System.currentTimeMillis());
            remainingExecution.setSubmittedAt(System.currentTimeMillis());
            remainingExecution.setCreatedAt(System.currentTimeMillis());
            remainingExecution.setUpdatedAt(System.currentTimeMillis());
            remainingExecution.setRetryCount(0);
            
            executionMapper.insert(remainingExecution);
            
            log.info("✅ [PartialLiquidationHandler] Remaining liquidation order created, " +
                    "liquidationId={}, remainingLiquidationId={}, newOrderId={}, orderType={}",
                    execution.getLiquidationId(), remainingExecution.getLiquidationId(), 
                    newOrderId, orderType);
            
        } catch (Exception e) {
            log.error("❌ [PartialLiquidationHandler] Failed to create remaining liquidation order, " +
                    "liquidationId={}, remainingQty={}",
                    execution.getLiquidationId(), remainingQty, e);
            throw new RuntimeException("Create remaining liquidation order failed", e);
        }
    }
    
    /**
     * 获取最新标记价格（兜底策略）
     *
     * 优先使用 execution 中已有的 markPrice，避免额外 RPC。
     * 若 markPrice 缺失，返回 null 由调用方 fallback 到 MARKET。
     */
    private Long getLatestMarkPrice(LiquidationExecution execution) {
        Long markPrice = execution.getMarkPrice();
        if (markPrice != null && markPrice > 0) {
            return markPrice;
        }
        log.warn("⚠️ [PartialLiquidationHandler] markPrice not available, fallback to MARKET, liquidationId={}",
                execution.getLiquidationId());
        return null;
    }
    
    /**
     * 判断订单类型（价格保护）
     * 
     * @param executedPrice 已成交价格
     * @param markPrice 标记价格
     * @return MARKET 或 LIMIT
     */
    private String determineOrderType(Long executedPrice, Long markPrice) {
        if (markPrice == null || executedPrice == null) {
            return "MARKET";
        }
        
        // 计算价格偏差
        long priceDiff = Math.abs(markPrice - executedPrice);
        long priceDiffPercent = (priceDiff * 100) / executedPrice;
        
        // 如果偏差 > 5%，使用限价单
        if (priceDiffPercent > 5) {
            return "LIMIT";
        } else {
            return "MARKET";
        }
    }
    
    /**
     * 生成剩余仓位强平ID
     */
    private String generateRemainingLiquidationId(String parentLiquidationId) {
        return parentLiquidationId + "_R" + System.currentTimeMillis();
    }
}



