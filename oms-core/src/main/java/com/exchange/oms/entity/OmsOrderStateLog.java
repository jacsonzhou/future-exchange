package com.exchange.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 订单状态日志实体
 */
@Data
@TableName("t_order_state_log")
public class OmsOrderStateLog {
    
    /**
     * 日志ID
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
     * 原状态
     */
    private Integer fromStatus;
    
    /**
     * 新状态
     */
    private Integer toStatus;
    
    /**
     * 原因码
     */
    private String reasonCode;
    
    /**
     * 原因描述
     */
    private String reasonMsg;
    
    /**
     * 操作者
     */
    private String operator;
    
    /**
     * 追踪ID
     */
    private String traceId;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}



