package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 风控检查请求（与 hard-risk-core 契约对齐）
 *
 * 字段类型必须与 hard-risk-core 的 CheckOrderRiskRequest 保持一致，
 * 否则 Feign + Jackson 反序列化时字段绑定会失败。
 */
@Data
public class CheckOrderRiskRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 追踪ID
     */
    private String traceId;

    /**
     * 请求ID
     */
    private String requestId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 买卖方向 BUY/SELL
     */
    private String side;

    /**
     * 价格（字符串格式，与 hard-risk-core 保持一致）
     */
    private String price;

    /**
     * 数量（字符串格式，与 hard-risk-core 保持一致）
     */
    private String quantity;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    /**
     * 是否只减仓
     */
    private Boolean reduceOnly;

    /**
     * 订单ID（OMS生成，用于幂等和审计）
     */
    private String orderId;
}
