package com.exchange.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.common.core.enums.OrderStatus;
import com.exchange.common.core.enums.OrderType;
import com.exchange.common.core.enums.Side;
import lombok.Data;

/**
 * 订单实体
 */
@Data
@TableName("t_order")
public class Order {
    
    /**
     * 订单ID
     */
    @TableId(type = IdType.INPUT)
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
     * 买卖方向
     */
    private Side side;
    
    /**
     * 订单类型
     */
    private OrderType orderType;
    
    /**
     * 价格
     */
    private Long price;
    
    /**
     * 数量
     */
    private Long quantity;
    
    /**
     * 已成交数量
     */
    private Long filledQuantity;
    
    /**
     * 订单状态
     */
    private OrderStatus status;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
    
    /**
     * 创建时间
     */
    private Long createTime;
    
    /**
     * 更新时间
     */
    private Long updateTime;
    
    /**
     * 是否只减仓
     */
    private Boolean reduceOnly;
    
    /**
     * 订单来源 (LIQUIDATION, ADL, TP_SL等)
     */
    private String orderSource;
    
    /**
     * 持仓ID (强平/ADL订单使用)
     */
    private Long positionId;
}




