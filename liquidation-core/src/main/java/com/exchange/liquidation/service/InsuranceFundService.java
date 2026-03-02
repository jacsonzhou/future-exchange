package com.exchange.liquidation.service;

import com.exchange.liquidation.entity.LiquidationExecution;

/**
 * 保险基金服务接口
 * 
 * 处理保险基金赔付逻辑
 */
public interface InsuranceFundService {
    
    /**
     * 申请保险基金赔付
     * 
     * @param execution 强平执行记录
     * @return 实际赔付金额（8位小数）
     */
    Long applyInsuranceCover(LiquidationExecution execution);
    
    /**
     * 查询保险基金余额
     *
     * @param symbol 交易对（可选）
     * @return 余额（8位小数）
     */
    Long getInsuranceFundBalance(String symbol);

    /**
     * 申请部分成交的保险基金赔付（增量赔付）
     *
     * 按已成交比例申请赔付，支持多次部分成交的累加
     *
     * @param execution 强平执行记录
     * @param partialBankruptLoss 本次部分成交的穿仓损失
     * @param filledQty 本次成交数量
     * @param totalQty 总数量
     * @return 实际赔付金额（8位小数）
     */
    Long applyPartialInsuranceCover(LiquidationExecution execution,
                                     Long partialBankruptLoss,
                                     Long filledQty,
                                     Long totalQty);

    /**
     * 将强平盈余注入保险基金
     *
     * 计算规则：当 (realizedPnl + initialMargin) > 0 且未穿仓时，
     * 该盈余金额可注入保险基金，调用方无需关心幂等细节。
     *
     * @param execution 强平执行记录
     * @return 实际注资金额（8位小数）
     */
    Long injectLiquidationSurplus(LiquidationExecution execution);
}
