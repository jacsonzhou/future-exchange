package com.exchange.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 风控审计日志实体
 */
@Data
@TableName("risk_check_log")
public class RiskCheckLog {
    
    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 风控结果 0=PASS 1=REJECT
     */
    private Integer result;
    
    /**
     * 拒绝原因
     */
    private Integer rejectReason;
    
    /**
     * 拒绝信息
     */
    private String rejectMessage;
    
    /**
     * 所需保证金
     */
    private BigDecimal requiredMargin;
    
    /**
     * 可用保证金
     */
    private BigDecimal availableMargin;
    
    /**
     * 追踪ID
     */
    private String traceId;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}

