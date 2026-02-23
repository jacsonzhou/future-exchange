package com.exchange.position.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Trade Entry Event（从Ledger接收）
 *
 * 🔥 核心职责：
 * 1. 消费Ledger-Core发布的账本分录事件
 * 2. 从分录中提取持仓变动信息
 * 3. 保证Ledger是唯一事实源
 *
 * Topic: trade-entry-{symbol}
 *
 * 修复说明：
 * - 原实现直接消费 trade-event（撮合事件），破坏了Ledger作为唯一事实源的原则
 * - 新实现消费 trade-entry-{symbol}（账本分录），确保只有Ledger记账成功后持仓才更新
 *
 * 🔥 重要修复：
 * - 添加成交原始信息（price, quantity等）
 * - 确保Position Service能正确计算持仓成本
 * - 避免从分录中错误提取价格导致的计算问题
 *
 * 对标：Binance / OKX / Bybit级别
 */
@Data
public class TradeEntryEvent {

    private String tradeId;
    private Long sequence;
    private String symbol;
    private List<LedgerEntry> entries;
    private Long eventTime;
    private Long bizSeq;

    // ==================== 🔥 新增：成交原始信息（用于Position计算） ====================

    /**
     * 成交价格
     * 用于Position Service正确计算持仓成本
     */
    private BigDecimal price;

    /**
     * 成交数量
     * 用于Position Service正确计算持仓变动
     */
    private BigDecimal quantity;

    /**
     * Maker是否买方
     * true: Maker买入（Taker卖出）
     * false: Maker卖出（Taker买入）
     */
    private Boolean isBuyerMaker;

    /**
     * Maker用户ID
     */
    private Long makerUserId;

    /**
     * Taker用户ID
     */
    private Long takerUserId;

    /**
     * Maker订单ID
     */
    private Long makerOrderId;

    /**
     * Taker订单ID
     */
    private Long takerOrderId;

    /**
     * Maker手续费
     */
    private BigDecimal makerFee;

    /**
     * Taker手续费
     */
    private BigDecimal takerFee;
    
    /**
     * Ledger分录
     * 
     * 每笔成交产生两条分录（借贷平衡）：
     * - 持仓资产变动（POSITION_ASSET）
     * - 保证金变动（MARGIN）
     * - 已实现盈亏（REALIZED_PNL）
     */
    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class LedgerEntry {
        private Long entryId;
        private Long userId;
        private Integer accountType;
        private String currency;
        private java.math.BigDecimal debit;
        private java.math.BigDecimal credit;
        private java.math.BigDecimal balanceBefore;
        private java.math.BigDecimal balanceAfter;
        private Long pairEntryId;
        private String businessType;
        private String refTradeId;
        private Long refOrderId;
        private Long bizSeq;
        private String idempotentKey;
        private Long createdAt;
        
        /**
         * 判断是否为持仓资产分录
         */
        public boolean isPositionAssetEntry() {
            return "POSITION_ASSET".equals(businessType) || 
                   "POSITION".equals(businessType);
        }
        
        /**
         * 判断是否为已实现盈亏分录
         */
        public boolean isRealizedPnlEntry() {
            return "REALIZED_PNL".equals(businessType) ||
                   "PNL".equals(businessType);
        }
        
        /**
         * 计算净变动（debit - credit）
         * 多头：正数表示增加持仓，负数表示减少持仓
         * 空头：负数表示增加持仓（绝对值），正数表示减少持仓
         */
        public java.math.BigDecimal getNetChange() {
            if (debit == null) debit = java.math.BigDecimal.ZERO;
            if (credit == null) credit = java.math.BigDecimal.ZERO;
            return debit.subtract(credit);
        }
    }
}
