package com.exchange.funding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资金费率配置实体
 */
@Data
@TableName("t_funding_rate_config")
public class FundingRateConfig {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 结算间隔(秒)，默认8小时
     */
    private Integer settlementInterval;
    
    /**
     * 最大资金费率(如750000=0.75%)
     */
    private Long maxRate;
    
    /**
     * 最小资金费率(如-750000=-0.75%)
     */
    private Long minRate;
    
    /**
     * 利率差(如0.01%=1000)
     */
    private Long interestRate;
    
    /**
     * 状态: 1启用 0禁用
     */
    private Integer status;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
