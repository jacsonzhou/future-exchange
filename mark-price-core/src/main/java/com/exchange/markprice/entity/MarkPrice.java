package com.exchange.markprice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标记价格实体
 */
@Data
@TableName("t_mark_price")
public class MarkPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 标记价格（8位精度）
     */
    private Long markPrice;

    /**
     * 指数价格（8位精度）
     */
    private Long indexPrice;

    /**
     * 资金费率
     */
    private Long fundingRate;

    /**
     * 下次结算时间
     */
    private Long nextFundingTime;

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
