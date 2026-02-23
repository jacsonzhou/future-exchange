package com.exchange.funding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户资金费用明细实体
 */
@Data
@TableName("t_user_funding_fee")
public class UserFundingFee {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 结算时间戳(毫秒)
     */
    private Long fundingTime;
    
    /**
     * 持仓方向: LONG/SHORT
     */
    private String side;
    
    /**
     * 持仓数量
     */
    private Long positionQty;
    
    /**
     * 标记价格
     */
    private Long markPrice;
    
    /**
     * 资金费率
     */
    private Long fundingRate;
    
    /**
     * 资金费用(正=支付,负=收取)
     */
    private Long fundingFee;
    
    /**
     * 保证金模式: ISOLATED/CROSS
     */
    private String marginMode;
    
    /**
     * 状态
     */
    private String status;
    
    private LocalDateTime createdAt;
}
