package com.exchange.market.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 成交事件模型
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
public class Trade {
    
    /**
     * 成交ID（全局唯一）
     */
    private long tradeId;
    
    /**
     * 撮合序号（严格递增，用于对账）
     */
    private long sequence;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 成交价格（8位小数，实际值=price/1e8）
     */
    private long price;
    
    /**
     * 成交数量（8位小数）
     */
    private long quantity;
    
    /**
     * 方向：true=买，false=卖（从taker视角）
     */
    private boolean isBuyerMaker;
    
    /**
     * 成交时间戳（毫秒）
     */
    private long timestamp;
    
    /**
     * 买方订单ID
     */
    private long buyerOrderId;
    
    /**
     * 卖方订单ID
     */
    private long sellerOrderId;
    
    /**
     * 成交类型：NORMAL, LIQUIDATION, ADL
     */
    private String tradeType;
    
    /**
     * 计算成交额
     */
    public long getQuoteQuantity() {
        return price * quantity;
    }
    
    /**
     * 清除数据（用于对象池）
     */
    public void clear() {
        this.tradeId = 0;
        this.sequence = 0;
        this.symbol = null;
        this.price = 0;
        this.quantity = 0;
        this.isBuyerMaker = false;
        this.timestamp = 0;
        this.buyerOrderId = 0;
        this.sellerOrderId = 0;
        this.tradeType = null;
    }
    
    /**
     * 是否有效成交
     */
    public boolean isValid() {
        return price > 0 && quantity > 0 && symbol != null;
    }
    
    @Override
    public String toString() {
        return String.format("Trade[id=%d, symbol=%s, price=%d, qty=%d, side=%s, time=%d]",
                tradeId, symbol, price, quantity, isBuyerMaker ? "BUY" : "SELL", timestamp);
    }
}
