package com.exchange.risk.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 账户快照实体
 */
@Data
@TableName("risk_account_snapshot")
public class RiskAccountSnapshot {
    
    /**
     * 账户ID
     */
    @TableId
    private Long accountId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 净资产
     */
    private BigDecimal equity;
    
    /**
     * 余额
     */
    private BigDecimal balance;
    
    /**
     * 可用保证金
     */
    private BigDecimal availableMargin;
    
    /**
     * 已用保证金
     */
    private BigDecimal usedMargin;
    
    /**
     * 保证金率
     */
    private BigDecimal marginRatio;
    
    /**
     * 账户状态 0=NORMAL 1=FROZEN 2=LIQUIDATING
     */
    private Integer accountStatus;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    /**
     * 是否正常状态
     */
    public boolean isNormal() {
        return accountStatus != null && accountStatus == 0;
    }
    
    /**
     * 是否冻结
     */
    public boolean isFrozen() {
        return accountStatus != null && accountStatus == 1;
    }
    
    /**
     * 是否强平中
     */
    public boolean isLiquidating() {
        return accountStatus != null && accountStatus == 2;
    }
}

