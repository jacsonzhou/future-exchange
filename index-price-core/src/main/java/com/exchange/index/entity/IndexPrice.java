package com.exchange.index.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指数价格实体
 */
@Data
@TableName("t_index_price")
public class IndexPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 指数价格（8位精度）
     */
    private Long price;

    /**
     * 价格来源（如：binance,okx,coinbase）
     */
    private String source;

    /**
     * 权重
     */
    private Integer weight;

    /**
     * 原始价格（来自交易所）
     */
    private Long rawPrice;

    /**
     * 数据时间戳
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
