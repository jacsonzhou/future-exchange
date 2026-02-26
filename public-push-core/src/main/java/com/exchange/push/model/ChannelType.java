package com.exchange.push.model;

import lombok.Getter;

/**
 * 频道类型枚举
 */
@Getter
public enum ChannelType {
    
    TRADE("trade", "实时成交"),
    AGG_TRADE("aggTrade", "聚合成交"),
    DEPTH("depth", "深度"),
    KLINE("kline", "K线"),
    TICKER("ticker", "24h统计"),
    EXT_TRADE("trade.ext", "外部实时成交"),
    EXT_DEPTH("depth.ext", "外部深度"),
    EXT_KLINE("kline.ext", "外部K线"),
    EXT_TICKER("ticker.ext", "外部24h统计"),
    MARK_PRICE("markPrice", "标记价格"),
    TICKER_ALL("ticker@arr", "全市场Ticker"),
    MARK_PRICE_ALL("markPrice@arr", "全市场标记价格"),
    UNKNOWN("unknown", "未知");
    
    private final String code;
    private final String description;
    
    ChannelType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public static ChannelType fromChannel(String channel) {
        if (channel.startsWith("trade.ext.")) {
            return EXT_TRADE;
        } else if (channel.startsWith("depth.ext.")) {
            return EXT_DEPTH;
        } else if (channel.startsWith("kline.ext.")) {
            return EXT_KLINE;
        } else if (channel.startsWith("ticker.ext.")) {
            return EXT_TICKER;
        } else if (channel.startsWith("trade.")) {
            return TRADE;
        } else if (channel.startsWith("aggTrade.")) {
            return AGG_TRADE;
        } else if (channel.startsWith("depth.")) {
            return DEPTH;
        } else if (channel.startsWith("kline.")) {
            return KLINE;
        } else if (channel.startsWith("ticker.")) {
            return TICKER;
        } else if (channel.startsWith("markPrice.")) {
            return MARK_PRICE;
        } else if (channel.equals("ticker@arr")) {
            return TICKER_ALL;
        } else if (channel.equals("markPrice@arr")) {
            return MARK_PRICE_ALL;
        }
        return UNKNOWN;
    }
}
