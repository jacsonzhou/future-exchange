package com.exchange.binance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 币安成交数据模型
 * 
 * 对应币安WebSocket trade/aggTrade事件
 * 同时用于承载Ticker数据（24h统计）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinanceTrade {

    // ========== 基础字段（Trade/AggTrade共用）==========

    /**
     * 交易对，如 BTCUSDT
     */
    private String symbol;

    /**
     * 事件时间（毫秒）
     */
    private long eventTime;

    /**
     * 成交ID
     * - Trade: t字段
     * - AggTrade: a字段
     * - Ticker: L字段（lastTradeId）
     */
    private long tradeId;

    /**
     * 成交价格（8位精度）
     */
    private long price;

    /**
     * 成交数量（8位精度）
     */
    private long quantity;

    /**
     * 成交时间（毫秒）
     * - Trade: T字段
     * - AggTrade: T字段
     * - Ticker: E字段（eventTime）
     */
    private long tradeTime;

    /**
     * 买方是否挂单方
     * true = 买方挂单（主动卖出）
     * false = 卖方挂单（主动买入）
     */
    private boolean isBuyerMaker;

    // ========== 聚合成交特有字段 ==========

    /**
     * 是否为聚合成交
     */
    @Builder.Default
    private boolean isAggTrade = false;

    /**
     * 聚合成交中的第一笔成交ID
     */
    private long firstTradeId;

    /**
     * 聚合成交中的最后一笔成交ID
     */
    private long lastTradeId;

    // ========== Ticker特有字段 ==========

    /**
     * 是否为Ticker数据
     */
    @Builder.Default
    private boolean isTicker = false;

    /**
     * 24小时开盘价
     */
    private long openPrice;

    /**
     * 24小时最高价
     */
    private long highPrice;

    /**
     * 24小时最低价
     */
    private long lowPrice;

    /**
     * 24小时成交量（币数量）
     */
    private long volume;

    /**
     * 24小时成交额（USDT数量）
     */
    private long quoteVolume;

    /**
     * 价格变化（相比24小时前）
     */
    private long priceChange;

    /**
     * 价格变化百分比
     */
    private double priceChangePercent;

    /**
     * 加权平均价
     */
    private long weightedAvgPrice;

    /**
     * 成交笔数
     */
    private int tradeCount;

    /**
     * 24小时统计开始时间
     */
    private long openTime;

    /**
     * 24小时统计结束时间
     */
    private long closeTime;

    // ========== 计算方法 ==========

    /**
     * 计算成交额（price * quantity）
     * 
     * @return 成交额（16位精度，需要除以1e16）
     */
    public long getQuoteQty() {
        return price * quantity;
    }

    /**
     * 获取方向描述
     * 
     * @return "SELL"（主动卖）或 "BUY"（主动买）
     */
    public String getSide() {
        return isBuyerMaker ? "SELL" : "BUY";
    }

    /**
     * 检查是否为有效成交数据
     */
    public boolean isValidTrade() {
        return !isTicker && symbol != null && !symbol.isEmpty() 
                && price > 0 && quantity > 0;
    }

    /**
     * 检查是否为有效Ticker数据
     */
    public boolean isValidTicker() {
        return isTicker && symbol != null && !symbol.isEmpty() 
                && highPrice > 0 && lowPrice > 0;
    }

    @Override
    public String toString() {
        if (isTicker) {
            return String.format("BinanceTicker[symbol=%s, price=%d, change=%.2f%%]",
                    symbol, price, priceChangePercent);
        } else if (isAggTrade) {
            return String.format("BinanceAggTrade[symbol=%s, id=%d, price=%d, qty=%d]",
                    symbol, tradeId, price, quantity);
        } else {
            return String.format("BinanceTrade[symbol=%s, id=%d, price=%d, qty=%d, side=%s]",
                    symbol, tradeId, price, quantity, getSide());
        }
    }
}
