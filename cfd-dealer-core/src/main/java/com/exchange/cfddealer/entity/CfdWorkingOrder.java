package com.exchange.cfddealer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("t_cfd_working_order")
public class CfdWorkingOrder {

    @TableId
    private Long orderId;

    private Long userId;

    private String symbol;

    /**
     * 方向: 0=BUY, 1=SELL
     */
    private Integer side;

    private BigDecimal limitPrice;

    private BigDecimal quantity;

    private BigDecimal remainingQuantity;

    private String status;

    private String triggerSource;

    private Long triggerEventTime;

    private Long createdAt;

    private Long updatedAt;

    private Integer version;
}
