package com.exchange.common.proto.request;

import com.exchange.common.core.enums.OrderType;
import com.exchange.common.core.enums.Side;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建订单请求
 */
@Data
public class CreateOrderRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对（如：BTCUSDT）
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
     * 价格（限价单必填，long格式）
     */
    private Long price;
    
    /**
     * 数量（long格式）
     */
    private Long quantity;
    
    /**
     * 杠杆倍数（合约专用）
     */
    private Integer leverage;
    
    /**
     * 客户端订单ID（幂等性）
     */
    private String clientOrderId;
    
    /**
     * 请求时间戳
     */
    private Long timestamp;
    
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

    /**
     * 强平ID（强平订单使用）
     */
    private String liquidationId;

    /**
     * 执行模式（MATCH_ENGINE / CFD_DEALER）
     * 内部订单（强平/ADL）可显式指定，未指定时由OMS路由配置决定
     */
    private String executionMode;
}






