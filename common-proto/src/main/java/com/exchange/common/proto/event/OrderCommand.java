package com.exchange.common.proto.event;

import com.exchange.common.core.enums.OrderType;
import com.exchange.common.core.enums.Side;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单命令事件（发送到撮合引擎）
 */
@Data
public class OrderCommand implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 命令类型
     */
    public enum CommandType {
        NEW_ORDER,      // 新订单
        CANCEL_ORDER    // 撤单
    }
    
    /**
     * 命令类型
     */
    private CommandType commandType;
    
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
     * 买卖方向
     */
    private Side side;
    
    /**
     * 订单类型
     */
    private OrderType orderType;
    
    /**
     * 价格（long格式）
     */
    private Long price;
    
    /**
     * 数量（long格式）
     */
    private Long quantity;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    /**
     * 序列号（用于WAL）
     */
    private Long sequence;
}







