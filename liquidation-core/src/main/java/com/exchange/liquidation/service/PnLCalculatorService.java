package com.exchange.liquidation.service;

import com.exchange.liquidation.entity.LiquidationExecution;

/**
 * 盈亏计算服务接口
 * 
 * 计算强平后的实际盈亏、穿仓损失等
 */
public interface PnLCalculatorService {
    
    /**
     * 计算强平盈亏
     * 
     * @param execution 强平执行记录
     * @return 计算后的执行记录（已更新盈亏字段）
     */
    LiquidationExecution calculatePnL(LiquidationExecution execution);
    
    /**
     * 计算穿仓损失
     * 
     * @param execution 强平执行记录
     * @return 穿仓损失（8位小数，正数表示损失金额）
     */
    Long calculateBankruptLoss(LiquidationExecution execution);
    
    /**
     * 检查是否需要ADL
     *
     * @param execution 强平执行记录
     * @return 是否需要ADL
     */
    boolean needsAdl(LiquidationExecution execution);

    /**
     * 计算部分成交的盈亏
     *
     * 用于部分成交场景的增量计算
     *
     * @param execution 强平执行记录
     * @param filledQty 本次成交数量（8位小数）
     * @param avgPrice 本次成交均价（8位小数）
     * @return 本次成交的盈亏（8位小数，负数为亏损）
     */
    Long calculatePartialPnL(LiquidationExecution execution, Long filledQty, Long avgPrice);

    /**
     * 计算部分成交的穿仓损失
     *
     * @param execution 强平执行记录
     * @param partialPnl 本次成交盈亏
     * @param filledQty 本次成交数量
     * @param totalQty 总数量
     * @return 本次成交的穿仓损失（8位小数，正数表示损失金额）
     */
    Long calculatePartialBankruptLoss(LiquidationExecution execution,
                                       Long partialPnl,
                                       Long filledQty,
                                       Long totalQty);
}

