package com.exchange.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * K线数据实体
 */
@Data
@TableName("t_kline")
public class Kline {

    @TableId(type = IdType.AUTO)
    private Long id;

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
    private Long openTime;

    /**
     * 收盘时间
     */
    private Long closeTime;

    /**
     * 开盘价
     */
    private Long openPrice;

    /**
     * 最高价
     */
    private Long highPrice;

    /**
     * 最低价
     */
    private Long lowPrice;

    /**
     * 收盘价
     */
    private Long closePrice;

    /**
     * 成交量
     */
    private Long volume;

    /**
     * 成交额
     */
    private Long quoteVolume;

    /**
     * 成交笔数
     */
    private Integer tradeCount;

    /**
     * 主动买入成交量
     */
    private Long takerBuyVolume;

    /**
     * 主动买入成交额
     */
    private Long takerBuyQuoteVolume;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
