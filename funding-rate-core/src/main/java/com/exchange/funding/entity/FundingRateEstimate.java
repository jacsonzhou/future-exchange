package com.exchange.funding.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预估资金费率实体
 */
@Data
@TableName("t_funding_rate_estimate")
public class FundingRateEstimate {

    @TableId
    private String symbol;

    /**
     * 下次结算时间
     */
    private Long nextFundingTime;

    /**
     * 预估资金费率
     */
    private Long estimatedRate;

    /**
     * 当前标记价格
     */
    private Long markPrice;

    /**
     * 当前指数价格
     */
    private Long indexPrice;

    /**
     * 当前溢价指数
     */
    private Long premiumIndex;

    private LocalDateTime updatedAt;
}
