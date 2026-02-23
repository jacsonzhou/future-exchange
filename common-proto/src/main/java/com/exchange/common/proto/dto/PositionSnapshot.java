package com.exchange.common.proto.dto;

import com.exchange.common.core.enums.Side;
import lombok.Data;

import java.io.Serializable;

/**
 * 持仓快照
 */
@Data
public class PositionSnapshot implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 持仓方向
     */
    private Side side;
    
    /**
     * 持仓数量
     */
    private Long quantity;
    
    /**
     * 开仓均价
     */
    private Long entryPrice;
    
    /**
     * 标记价格（用于计算浮盈浮亏）
     */
    private Long markPrice;
    
    /**
     * 保证金
     */
    private Long margin;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 未实现盈亏
     */
    private Long unrealizedPnl;
    
    /**
     * 强平价格
     */
    private Long liquidationPrice;
    
    /**
     * 风险率（百分比，100 = 100%）
     */
    private Long marginRatio;
    
    /**
     * 更新时间
     */
    private Long updateTime;
}




