package com.exchange.marketmaker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 做市商实体
 */
@Data
@TableName("t_market_maker")
public class MarketMaker {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long userId;
    
    // 等级信息
    private Integer level;
    private String status;
    
    // 费率配置
    private Long makerFeeRate;
    private Long takerFeeRate;
    
    // 限制配置
    private Integer apiLimitPerSec;
    private Integer maxOrderCount;
    private Long maxPositionValue;
    
    // 考核周期
    private LocalDate evalPeriodStart;
    private LocalDate evalPeriodEnd;
    
    // 账户信息
    private Long mmAccountId;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
