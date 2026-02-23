package com.exchange.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 交易对风控配置实体
 */
@Data
@TableName("risk_symbol_config")
public class RiskSymbolConfig {
    
    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 最大杠杆
     */
    private Integer maxLeverage;
    
    /**
     * 最大持仓数量
     */
    private BigDecimal maxPositionQty;
    
    /**
     * 最大价格偏离百分比
     */
    private BigDecimal maxPriceDeviationPct;
    
    /**
     * 最小下单数量
     */
    private BigDecimal minOrderQty;
    
    /**
     * 最大下单数量
     */
    private BigDecimal maxOrderQty;
    
    /**
     * 状态 0=禁用 1=启用
     */
    private Integer status;
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}

