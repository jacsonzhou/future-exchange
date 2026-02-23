package com.exchange.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订单事件实体
 */
@Data
@TableName("t_order_event")
public class OmsOrderEvent {
    
    /**
     * 事件ID
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
     * 事件类型
     */
    private String eventType;
    
    /**
     * 事件来源
     */
    private String eventSource;
    
    /**
     * 事件数据（JSON）
     */
    private String eventPayload;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}



