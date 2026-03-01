package com.exchange.ledger.dto;

import com.exchange.common.core.Money;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.math.BigInteger;

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
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeDTO {
    private static final BigDecimal SCALED_THRESHOLD = new BigDecimal("10000000");
    
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
        return toDecimal(price, true, "price");
    }
    
    /**
     * 获取数量（BigDecimal）
     * 用于内部计算，自动转换 Long 为 BigDecimal
     */
    public BigDecimal getQuantityAsBigDecimal() {
        return toDecimal(quantity, true, "quantity");
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
    @JsonAlias("isBuyerMaker")
    private Boolean isMakerBuy;

    /**
     * Maker 杠杆倍数（从 OMS 透传，默认 10）
     */
    private Integer makerLeverage;

    /**
     * Taker 杠杆倍数（从 OMS 透传，默认 10）
     */
    private Integer takerLeverage;

    /**
     * 执行模式：MATCH_ENGINE / CFD_DEALER
     */
    private String executionMode;

    /**
     * 流动性来源：如 BINANCE_REF
     */
    private String liquiditySource;

    /**
     * 参考行情来源 Topic
     */
    private String referenceTopic;

    /**
     * 参考行情来源 offset
     */
    private Long referenceOffset;

    /**
     * 参考行情事件时间（毫秒）
     */
    private Long referenceEventTime;

    /**
     * 平台对手方账户（CFD 模式）
     */
    private Long dealerAccountId;
    
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
        return toDecimal(makerFee, false, "makerFee");
    }
    
    /**
     * 获取 Taker 手续费（BigDecimal）
     */
    public BigDecimal getTakerFeeAsBigDecimal() {
        return toDecimal(takerFee, false, "takerFee");
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
    @JsonAlias("timestamp")
    private Long tradeTime;
    
    /**
     * 撮合序列号
     */
    @JsonAlias("sequence")
    private Long matchSequence;

    public int getMakerLeverageOrDefault() {
        return (makerLeverage == null || makerLeverage <= 0) ? 10 : makerLeverage;
    }

    public int getTakerLeverageOrDefault() {
        return (takerLeverage == null || takerLeverage <= 0) ? 10 : takerLeverage;
    }

    private BigDecimal toDecimal(Object value, boolean strict, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return Money.toBigDecimal(((BigInteger) value).longValue());
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return Money.toBigDecimal(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            BigDecimal parsed = new BigDecimal(text);
            if (looksLikeScaledNumber(text, parsed)) {
                return Money.toBigDecimal(parsed.longValue());
            }
            return parsed;
        }
        if (strict) {
            throw new IllegalArgumentException("Unsupported " + fieldName + " type: " + value.getClass());
        }
        return null;
    }

    private boolean looksLikeScaledNumber(String text, BigDecimal value) {
        int dotIndex = text.indexOf('.');
        if (dotIndex < 0) {
            return value.abs().compareTo(SCALED_THRESHOLD) >= 0;
        }
        String fraction = text.substring(dotIndex + 1);
        boolean fractionAllZero = !fraction.isEmpty() && fraction.chars().allMatch(ch -> ch == '0');
        return fractionAllZero && value.abs().compareTo(SCALED_THRESHOLD) >= 0;
    }
}
