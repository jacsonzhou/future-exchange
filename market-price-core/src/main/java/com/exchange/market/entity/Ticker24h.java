package com.exchange.market.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 24小时统计实体
 */
@Data
@TableName("t_ticker_24h")
public class Ticker24h {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 价格变动
     */
    private Long priceChange;

    /**
     * 价格变动百分比
     */
    private Long priceChangePercent;

    /**
     * 加权平均价格
     */
    private Long weightedAvgPrice;

    /**
     * 最近成交价
     */
    private Long lastPrice;

    /**
     * 最新成交量
     */
    private Long lastQty;

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
     * 成交量
     */
    private Long volume;

    /**
     * 成交额
     */
    private Long quoteVolume;

    /**
     * 开盘时间
     */
    private Long openTime;

    /**
     * 收盘时间
     */
    private Long closeTime;

    /**
     * 第一笔成交价
     */
    private Long firstId;

    /**
     * 最后一笔成交价
     */
    private Long lastId;

    /**
     * 成交笔数
     */
    private Integer count;

    /**
     * 数据更新时间
     */
    private Long timestamp;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
