package com.exchange.marketmaker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 做市商报价快照实体
 * 用于计算挂单时间占比、买卖价差等指标
 */
@Data
@TableName("t_mm_quote_snapshot")
public class MmQuoteSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String symbol;

    // 快照时间
    private LocalDateTime snapshotTime;

    // 买方深度
    private Long bidPrice1;
    private Long bidQty1;
    private Long bidPrice2;
    private Long bidQty2;
    private Long bidPrice3;
    private Long bidQty3;

    // 卖方深度
    private Long askPrice1;
    private Long askQty1;
    private Long askPrice2;
    private Long askQty2;
    private Long askPrice3;
    private Long askQty3;

    // 价差
    private Long spread;  // 买一卖一价差

    // 总深度
    private Long totalBidQty;
    private Long totalAskQty;

    private LocalDateTime createdAt;
}
