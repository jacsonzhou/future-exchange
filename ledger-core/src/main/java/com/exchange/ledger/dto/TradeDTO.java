package com.exchange.ledger.dto;

import com.exchange.common.core.Money;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Trade DTO（从Match Engine接收）
 * 
 * 对应Kafka: trade-event
 * 
 * 修复：支持 match-engine-core 发布的 long 格式（Money 格式）
 * - price/quantity 可以是 Long（Money 格式）或 BigDecimal（向后兼容）
 * - 提供转换方法供内部计算使用
 */
@Data
public class TradeDTO {
    
    /**
     * 成交ID（全局唯一）
     */
    private String tradeId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * Maker订单ID
     */
    private Long makerOrderId;
    
    /**
     * Taker订单ID
     */
    private Long takerOrderId;
    
    /**
     * Maker用户ID
     */
    private Long makerUserId;
    
    /**
     * Taker用户ID
     */
    private Long takerUserId;
    
    /**
     * 成交价格（修复：支持 Long 或 BigDecimal）
     * - Long: match-engine-core 发布的 Money 格式（推荐）
     * - BigDecimal: 向后兼容
     */
    private Object price;
    
    /**
     * 成交数量（修复：支持 Long 或 BigDecimal）
     * - Long: match-engine-core 发布的 Money 格式（推荐）
     * - BigDecimal: 向后兼容
     */
    private Object quantity;
    
    /**
     * 获取价格（BigDecimal）
     * 用于内部计算，自动转换 Long 为 BigDecimal
     */
    public BigDecimal getPriceAsBigDecimal() {
        if (price == null) {
            return null;
        }
        if (price instanceof BigDecimal) {
            return (BigDecimal) price;
        }
        if (price instanceof Long) {
            return Money.toBigDecimal((Long) price);
        }
        if (price instanceof Number) {
            return BigDecimal.valueOf(((Number) price).doubleValue());
        }
        throw new IllegalArgumentException("Unsupported price type: " + price.getClass());
    }
    
    /**
     * 获取数量（BigDecimal）
     * 用于内部计算，自动转换 Long 为 BigDecimal
     */
    public BigDecimal getQuantityAsBigDecimal() {
        if (quantity == null) {
            return null;
        }
        if (quantity instanceof BigDecimal) {
            return (BigDecimal) quantity;
        }
        if (quantity instanceof Long) {
            return Money.toBigDecimal((Long) quantity);
        }
        if (quantity instanceof Number) {
            return BigDecimal.valueOf(((Number) quantity).doubleValue());
        }
        throw new IllegalArgumentException("Unsupported quantity type: " + quantity.getClass());
    }
    
    /**
     * 获取价格（向后兼容方法）
     * @deprecated 使用 getPriceAsBigDecimal() 替代
     */
    @Deprecated
    public BigDecimal getPrice() {
        return getPriceAsBigDecimal();
    }
    
    /**
     * 获取数量（向后兼容方法）
     * @deprecated 使用 getQuantityAsBigDecimal() 替代
     */
    @Deprecated
    public BigDecimal getQuantity() {
        return getQuantityAsBigDecimal();
    }
    
    /**
     * Maker是否买方
     */
    private Boolean isMakerBuy;
    
    /**
     * Maker手续费（修复：支持 Long 或 BigDecimal）
     */
    private Object makerFee;
    
    /**
     * Taker手续费（修复：支持 Long 或 BigDecimal）
     */
    private Object takerFee;
    
    /**
     * 获取 Maker 手续费（BigDecimal）
     */
    public BigDecimal getMakerFeeAsBigDecimal() {
        if (makerFee == null) {
            return null;
        }
        if (makerFee instanceof BigDecimal) {
            return (BigDecimal) makerFee;
        }
        if (makerFee instanceof Long) {
            return Money.toBigDecimal((Long) makerFee);
        }
        if (makerFee instanceof Number) {
            return BigDecimal.valueOf(((Number) makerFee).doubleValue());
        }
        return null;
    }
    
    /**
     * 获取 Taker 手续费（BigDecimal）
     */
    public BigDecimal getTakerFeeAsBigDecimal() {
        if (takerFee == null) {
            return null;
        }
        if (takerFee instanceof BigDecimal) {
            return (BigDecimal) takerFee;
        }
        if (takerFee instanceof Long) {
            return Money.toBigDecimal((Long) takerFee);
        }
        if (takerFee instanceof Number) {
            return BigDecimal.valueOf(((Number) takerFee).doubleValue());
        }
        return null;
    }
    
    /**
     * 获取 Maker 手续费（向后兼容方法）
     * @deprecated 使用 getMakerFeeAsBigDecimal() 替代
     */
    @Deprecated
    public BigDecimal getMakerFee() {
        return getMakerFeeAsBigDecimal();
    }
    
    /**
     * 获取 Taker 手续费（向后兼容方法）
     * @deprecated 使用 getTakerFeeAsBigDecimal() 替代
     */
    @Deprecated
    public BigDecimal getTakerFee() {
        return getTakerFeeAsBigDecimal();
    }
    
    /**
     * 成交时间
     */
    private Long tradeTime;
    
    /**
     * 撮合序列号
     */
    private Long matchSequence;
}

