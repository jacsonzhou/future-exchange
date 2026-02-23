package com.exchange.tpsl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * TP/SL订单实体
 */
@Data
@TableName("t_tp_sl_order")
public class TpSlOrder {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long orderId;
    private Long userId;
    private String symbol;
    private String clientOrderId;
    
    // 关联信息
    private Long positionId;
    private Long parentOrderId;
    
    // 订单类型: TP/SL/TPSL/TRAILING
    private String orderType;
    private String triggerType;
    private String triggerDirection;
    
    // 触发条件
    private Long triggerPrice;
    private String triggerSide;
    private Boolean closeSide;
    
    // 执行配置
    private String execType;
    private Long execPrice;
    private Long quantity;
    
    // 移动止损配置
    private Long trailingOffset;
    private Long trailingPercent;
    private Long trailingCallbackRate;
    private Long trailingCallbackDistance;
    private Long trailingActivePrice;
    private Long highestPrice;
    
    // 状态: PENDING/ACTIVE/TRIGGERED/EXECUTED/CANCELLED/EXPIRED
    private String status;
    private Long triggerTime;
    private Long triggeredTime;
    private Long triggeredPrice;
    private Long execOrderId;
    private Long closeOrderId;
    private String execResult;
    private Long expireTime;
    
    // 时间戳
    private Long createTime;
    private Long updateTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
