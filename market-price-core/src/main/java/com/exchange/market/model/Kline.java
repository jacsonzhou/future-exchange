package com.exchange.market.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * K线数据模型（内存使用）
 * 
 * 设计目标：
 * - 轻量级，适合高频传输
 * - 避免使用BigDecimal，使用long存储（固定精度）
 * - 支持对象池复用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Kline {
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * K线周期 (1m, 5m, 15m, 1h, 4h, 1d)
     */
    private String interval;
    
    /**
     * 开盘时间
     */
    private long openTime;
    
    /**
     * 收盘时间
     */
    private long closeTime;
    
    /**
     * 开盘价
     */
    private long openPrice;
    
    /**
     * 最高价
     */
    private long highPrice;
    
    /**
     * 最低价
     */
    private long lowPrice;
    
    /**
     * 收盘价
     */
    private long closePrice;
    
    /**
     * 成交量
     */
    private long volume;
    
    /**
     * 成交额
     */
    private long quoteVolume;
    
    /**
     * 成交笔数
     */
    private int tradeCount;
    
    /**
     * 主动买入成交量
     */
    private long takerBuyVolume;
    
    /**
     * 主动买入成交额
     */
    private long takerBuyQuoteVolume;
    
    /**
     * 清除数据（用于对象池）
     */
    public void clear() {
        this.symbol = null;
        this.interval = null;
        this.openTime = 0;
        this.closeTime = 0;
        this.openPrice = 0;
        this.highPrice = 0;
        this.lowPrice = 0;
        this.closePrice = 0;
        this.volume = 0;
        this.quoteVolume = 0;
        this.tradeCount = 0;
        this.takerBuyVolume = 0;
        this.takerBuyQuoteVolume = 0;
    }
    
    private static final long PRICE_SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(PRICE_SCALE);
    
    /**
     * 转换为数组格式（与Binance API兼容）
     * 价格和数量从 Money 格式（8位小数）转换为 double
     */
    public Object[] toArray() {
        return new Object[] {
            openTime,
            formatScaled(openPrice),
            formatScaled(highPrice),
            formatScaled(lowPrice),
            formatScaled(closePrice),
            formatScaled(volume),
            closeTime,
            formatScaled(quoteVolume),
            tradeCount,
            formatScaled(takerBuyVolume),
            formatScaled(takerBuyQuoteVolume)
        };
    }

    private String formatScaled(long value) {
        return BigDecimal.valueOf(value)
                .divide(SCALE_BD, 8, RoundingMode.HALF_UP)
                .toPlainString();
    }
    
    @Override
    public String toString() {
        return String.format("Kline[%s %s O=%d H=%d L=%d C=%d V=%d]", 
                symbol, interval, openPrice, highPrice, lowPrice, closePrice, volume);
    }
}
