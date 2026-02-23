package com.exchange.funding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资金费率历史实体
 */
@Data
@TableName("t_funding_rate_history")
public class FundingRateHistory {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 结算时间戳(毫秒)
     */
    private Long fundingTime;
    
    /**
     * 资金费率(如50000=0.005%)
     */
    private Long fundingRate;
    
    /**
     * 标记价格
     */
    private Long markPrice;
    
    /**
     * 指数价格
     */
    private Long indexPrice;
    
    /**
     * 溢价指数
     */
    private Long premiumIndex;
    
    /**
     * 多头总持仓量
     */
    private Long totalLongQty;
    
    /**
     * 空头总持仓量
     */
    private Long totalShortQty;
    
    /**
     * 本次结算总金额
     */
    private Long settlementAmount;
    
    private LocalDateTime createdAt;
}
