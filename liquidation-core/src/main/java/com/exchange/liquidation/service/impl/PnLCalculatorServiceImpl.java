package com.exchange.liquidation.service.impl;

import com.exchange.liquidation.entity.LiquidationExecution;
import com.exchange.liquidation.service.PnLCalculatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 盈亏计算服务实现
 * 
 * 计算强平后的实际盈亏、穿仓损失等
 * 
 * 计算公式：
 * 1. 已实现盈亏 = (成交价格 - 开仓均价) × 数量 × 方向
 * 2. 穿仓损失 = min(已实现盈亏 + 初始保证金, 0) 的绝对值
 * 3. 剩余穿仓损失 = 穿仓损失 - 保险基金赔付
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PnLCalculatorServiceImpl implements PnLCalculatorService {
    
    @Override
    public LiquidationExecution calculatePnL(LiquidationExecution execution) {
        log.info("🧮 [PnLCalculatorService] Calculating PnL, liquidationId={}, orderId={}",
                execution.getLiquidationId(), execution.getOrderId());

        // 校验必要字段
        if (execution.getExecutedPrice() == null || execution.getExecutedQty() == null ||
            execution.getEntryPrice() == null || execution.getInitialMargin() == null) {
            log.error("❌ [PnLCalculatorService] Missing required fields for PnL calculation, " +
                    "liquidationId={}", execution.getLiquidationId());
            throw new IllegalArgumentException("Missing required fields for PnL calculation");
        }

        // 校验数据合理性
        if (execution.getExecutedPrice() <= 0 || execution.getExecutedQty() <= 0 ||
            execution.getEntryPrice() <= 0) {
            log.error("❌ [PnLCalculatorService] Invalid price or quantity, " +
                    "liquidationId={}, executedPrice={}, executedQty={}, entryPrice={}",
                    execution.getLiquidationId(), execution.getExecutedPrice(),
                    execution.getExecutedQty(), execution.getEntryPrice());
            throw new IllegalArgumentException("Price and quantity must be positive");
        }

        // 1. 计算已实现盈亏
        // 使用BigDecimal避免溢出，保证大仓位计算准确性
        long realizedPnl = calculateRealizedPnL(execution);
        execution.setRealizedPnl(realizedPnl);

        // 2. 计算穿仓损失
        long bankruptLoss = calculateBankruptLoss(execution);
        execution.setBankruptLoss(bankruptLoss);

        // 3. 计算剩余穿仓损失（保险基金赔付后）
        if (bankruptLoss > 0 && execution.getInsuranceCover() != null) {
            long remainingLoss = bankruptLoss - execution.getInsuranceCover();
            execution.setRemainingLoss(Math.max(remainingLoss, 0)); // 不能为负
        } else {
            execution.setRemainingLoss(0L);
        }

        // 4. 检查是否需要ADL
        boolean adlRequired = needsAdl(execution);
        execution.setAdlRequired(adlRequired ? 1 : 0);

        log.info("✅ [PnLCalculatorService] PnL calculated, liquidationId={}, " +
                "realizedPnl={}, bankruptLoss={}, insuranceCover={}, remainingLoss={}, adlRequired={}",
                execution.getLiquidationId(),
                realizedPnl, bankruptLoss,
                execution.getInsuranceCover(), execution.getRemainingLoss(), adlRequired);

        return execution;
    }

    /**
     * 计算已实现盈亏
     *
     * 使用BigDecimal避免大仓位溢出
     *
     * 公式：
     * 多仓：盈亏 = (成交价 - 开仓价) × 数量
     * 空仓：盈亏 = (开仓价 - 成交价) × 数量
     *
     * @param execution 强平执行记录
     * @return 已实现盈亏（8位小数格式）
     */
    private long calculateRealizedPnL(LiquidationExecution execution) {
        // 方向：1=多仓(+1)，2=空仓(-1)
        int direction = execution.getPositionSide() == 1 ? 1 : -1;

        // 使用BigDecimal计算，避免溢出
        // 所有值都是8位小数格式，例如 50000.00000000 → 5000000000000

        // 价格差
        BigDecimal executedPrice = BigDecimal.valueOf(execution.getExecutedPrice());
        BigDecimal entryPrice = BigDecimal.valueOf(execution.getEntryPrice());
        BigDecimal priceDiff = executedPrice.subtract(entryPrice);

        // 数量
        BigDecimal qty = BigDecimal.valueOf(execution.getExecutedQty());

        // 方向
        BigDecimal directionBd = BigDecimal.valueOf(direction);

        // 除数（100,000,000 用于8位小数转换）
        BigDecimal divisor = BigDecimal.valueOf(100_000_000L);

        // 盈亏 = (价格差 × 方向 × 数量) / 100,000,000
        // 这样保持8位小数格式
        BigDecimal realizedPnl = priceDiff
                .multiply(directionBd)
                .multiply(qty)
                .divide(divisor, 0, RoundingMode.DOWN);  // 向下取整

        long result = realizedPnl.longValue();

        log.debug("💰 PnL calculation details: executedPrice={}, entryPrice={}, qty={}, " +
                "direction={}, priceDiff={}, realizedPnl={}",
                execution.getExecutedPrice(), execution.getEntryPrice(), execution.getExecutedQty(),
                direction, priceDiff, result);

        return result;
    }
    
    @Override
    public Long calculateBankruptLoss(LiquidationExecution execution) {
        if (execution.getRealizedPnl() == null || execution.getInitialMargin() == null) {
            return 0L;
        }

        // 穿仓损失 = min(已实现盈亏 + 初始保证金, 0) 的绝对值
        // 如果 已实现盈亏 + 初始保证金 < 0，说明穿仓

        // 使用BigDecimal避免溢出
        BigDecimal realizedPnl = BigDecimal.valueOf(execution.getRealizedPnl());
        BigDecimal initialMargin = BigDecimal.valueOf(execution.getInitialMargin());
        BigDecimal total = realizedPnl.add(initialMargin);

        if (total.compareTo(BigDecimal.ZERO) < 0) {
            // 穿仓，返回损失金额（正数）
            return total.negate().longValue();
        } else {
            // 未穿仓
            return 0L;
        }
    }
    
    @Override
    public boolean needsAdl(LiquidationExecution execution) {
        // 如果剩余穿仓损失 > 0，需要ADL
        return execution.getRemainingLoss() != null && execution.getRemainingLoss() > 0;
    }

    @Override
    public Long calculatePartialPnL(LiquidationExecution execution, Long filledQty, Long avgPrice) {
        log.info("🧮 [PnLCalculatorService] Calculating partial PnL, liquidationId={}, " +
                "filledQty={}, avgPrice={}",
                execution.getLiquidationId(), filledQty, avgPrice);

        // 校验必要字段
        if (filledQty == null || avgPrice == null || execution.getEntryPrice() == null) {
            log.error("❌ [PnLCalculatorService] Missing required fields for partial PnL calculation, " +
                    "liquidationId={}", execution.getLiquidationId());
            throw new IllegalArgumentException("Missing required fields for partial PnL calculation");
        }

        // 校验数据合理性
        if (filledQty <= 0 || avgPrice <= 0 || execution.getEntryPrice() <= 0) {
            log.error("❌ [PnLCalculatorService] Invalid price or quantity, " +
                    "liquidationId={}, filledQty={}, avgPrice={}, entryPrice={}",
                    execution.getLiquidationId(), filledQty, avgPrice, execution.getEntryPrice());
            throw new IllegalArgumentException("Price and quantity must be positive");
        }

        // 方向：1=多仓(+1)，2=空仓(-1)
        int direction = execution.getPositionSide() == 1 ? 1 : -1;

        // 使用BigDecimal计算，避免溢出
        // 价格差
        BigDecimal executedPrice = BigDecimal.valueOf(avgPrice);
        BigDecimal entryPrice = BigDecimal.valueOf(execution.getEntryPrice());
        BigDecimal priceDiff = executedPrice.subtract(entryPrice);

        // 数量
        BigDecimal qty = BigDecimal.valueOf(filledQty);

        // 方向
        BigDecimal directionBd = BigDecimal.valueOf(direction);

        // 除数（100,000,000 用于8位小数转换）
        BigDecimal divisor = BigDecimal.valueOf(100_000_000L);

        // 盈亏 = (价格差 × 方向 × 数量) / 100,000,000
        BigDecimal partialPnl = priceDiff
                .multiply(directionBd)
                .multiply(qty)
                .divide(divisor, 0, RoundingMode.DOWN);

        long result = partialPnl.longValue();

        log.info("✅ [PnLCalculatorService] Partial PnL calculated, liquidationId={}, " +
                "filledQty={}, avgPrice={}, partialPnl={}",
                execution.getLiquidationId(), filledQty, avgPrice, result);

        return result;
    }

    @Override
    public Long calculatePartialBankruptLoss(LiquidationExecution execution,
                                              Long partialPnl,
                                              Long filledQty,
                                              Long totalQty) {
        log.info("🧮 [PnLCalculatorService] Calculating partial bankrupt loss, liquidationId={}, " +
                "partialPnl={}, filledQty={}, totalQty={}",
                execution.getLiquidationId(), partialPnl, filledQty, totalQty);

        if (partialPnl == null || filledQty == null || totalQty == null ||
            execution.getInitialMargin() == null) {
            log.warn("⚠️ [PnLCalculatorService] Missing required fields for partial bankrupt loss, " +
                    "returning 0");
            return 0L;
        }

        if (filledQty <= 0 || totalQty <= 0) {
            log.error("❌ [PnLCalculatorService] Invalid quantities, filledQty={}, totalQty={}",
                    filledQty, totalQty);
            throw new IllegalArgumentException("Quantities must be positive");
        }

        // 计算本次成交占用的保证金 = 初始保证金 × (成交数量 / 总数量)
        BigDecimal initialMargin = BigDecimal.valueOf(execution.getInitialMargin());
        BigDecimal filled = BigDecimal.valueOf(filledQty);
        BigDecimal total = BigDecimal.valueOf(totalQty);
        BigDecimal ratio = filled.divide(total, 8, RoundingMode.DOWN);
        BigDecimal partialMargin = initialMargin.multiply(ratio)
                .setScale(0, RoundingMode.DOWN);

        // 穿仓损失 = min(盈亏 + 保证金, 0) 的绝对值
        BigDecimal pnl = BigDecimal.valueOf(partialPnl);
        BigDecimal sum = pnl.add(partialMargin);

        long result = 0L;
        if (sum.compareTo(BigDecimal.ZERO) < 0) {
            // 穿仓，返回损失金额（正数）
            result = sum.negate().longValue();
        }

        log.info("✅ [PnLCalculatorService] Partial bankrupt loss calculated, liquidationId={}, " +
                "partialMargin={}, partialPnl={}, partialBankruptLoss={}",
                execution.getLiquidationId(), partialMargin, partialPnl, result);

        return result;
    }
}

