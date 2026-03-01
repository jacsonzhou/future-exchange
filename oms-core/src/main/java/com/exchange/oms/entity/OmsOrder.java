package com.exchange.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;

/**
 * OMS订单实体（增强版）
 * 
 * 设计要点：
 * 1. 订单当前态
 * 2. 乐观锁（version）
 * 3. 状态机驱动
 * 4. 幂等支持
 */
@Data
@TableName("t_order")
public class OmsOrder {
    
    /**
     * 订单ID（雪花算法）
     */
    @TableId(type = IdType.INPUT)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 客户端订单ID（幂等key）
     */
    private String clientOrderId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向 0=BUY 1=SELL
     */
    private Integer side;
    
    /**
     * 订单类型 0=LIMIT 1=MARKET
     */
    private Integer type;
    
    /**
     * 价格（限价单必填）
     */
    private BigDecimal price;
    
    /**
     * 数量
     */
    private BigDecimal quantity;
    
    /**
     * 已成交数量
     */
    private BigDecimal filledQuantity;
    
    /**
     * 订单状态
     * 0=NEW 1=PENDING_RISK 2=FROZEN 3=PARTIALLY_FILLED 4=FILLED 5=CANCELED 6=REJECTED
     */
    private Integer status;
    
    /**
     * 有效期类型
     */
    private String timeInForce;

    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 风控检查状态 0=未检查 1=通过 2=拒绝
     */
    private Integer riskCheckStatus;
    
    /**
     * 冻结状态 0=未冻结 1=已冻结
     */
    private Integer freezeStatus;
    
    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    /**
     * 剩余数量
     */
    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(filledQuantity);
    }
    
    /**
     * 是否完全成交
     */
    public boolean isFullyFilled() {
        return filledQuantity.compareTo(quantity) >= 0;
    }
    
    /**
     * 是否部分成交
     */
    public boolean isPartiallyFilled() {
        return filledQuantity.compareTo(BigDecimal.ZERO) > 0 
            && filledQuantity.compareTo(quantity) < 0;
    }
    
    /**
     * 是否可撤销
     */
    public boolean isCancelable() {
        // NEW, PENDING_RISK, FROZEN, PARTIALLY_FILLED 可撤销
        return status == 0 || status == 1 || status == 2 || status == 3;
    }
    
    /**
     * 是否终态
     */
    public boolean isFinalStatus() {
        // FILLED, CANCELED, REJECTED 为终态
        return status == 4 || status == 5 || status == 6;
    }
}


