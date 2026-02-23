package com.exchange.risk.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓快照实体
 */
@Data
@TableName("risk_position_snapshot")
public class RiskPositionSnapshot {
    
    /**
     * 持仓ID
     */
    @TableId
    private Long positionId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 方向 0=LONG 1=SHORT
     */
    private Integer side;
    
    /**
     * 数量
     */
    private BigDecimal quantity;
    
    /**
     * 开仓价
     */
    private BigDecimal entryPrice;
    
    /**
     * 标记价格
     */
    private BigDecimal markPrice;
    
    /**
     * 未实现盈亏
     */
    private BigDecimal unrealizedPnl;
    
    /**
     * 占用保证金
     */
    private BigDecimal usedMargin;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 强平价
     */
    private BigDecimal liquidationPrice;
    
    /**
     * 持仓状态 0=NORMAL 1=LIQUIDATING 2=CLOSED
     */
    private Integer positionStatus;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    /**
     * 是否多头
     */
    public boolean isLong() {
        return side != null && side == 0;
    }
    
    /**
     * 是否空头
     */
    public boolean isShort() {
        return side != null && side == 1;
    }
    
    /**
     * 是否正常状态
     */
    public boolean isNormal() {
        return positionStatus != null && positionStatus == 0;
    }
}

