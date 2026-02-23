package com.exchange.common.proto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 私有推送事件 (Private Push Event)
 * 
 * 🔥 统一用户私有数据推送格式
 * 
 * 事件类型:
 * - EXECUTION_REPORT: 订单执行报告 (成交、撤单、状态变更)
 * - ACCOUNT_UPDATE: 账户资金变化
 * - POSITION_UPDATE: 持仓变化
 * - BALANCE_UPDATE: 余额更新
 * - FUNDING_FEE: 资金费结算
 * 
 * 设计原则:
 * 1. 兼容 Binance executionReport 格式
 * 2. 支持 ACK 确认机制
 * 3. 包含序列号保证有序
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivatePushEvent {
    
    // ==================== 基础字段 ====================
    
    /**
     * 事件类型
     * @see EventType
     */
    private String e;
    
    /**
     * 事件时间 (毫秒时间戳)
     */
    private Long E;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 序列号 (用于排序和去重)
     */
    private Long seq;
    
    // ==================== 订单相关字段 (EXECUTION_REPORT) ====================
    
    /**
     * 交易对
     */
    private String s;
    
    /**
     * 订单ID
     */
    private Long i;
    
    /**
     * 客户端订单ID
     */
    private String c;
    
    /**
     * 买卖方向 (BUY/SELL)
     */
    private String S;
    
    /**
     * 订单类型 (LIMIT/MARKET/STOP_LIMIT...)
     */
    private String o;
    
    /**
     * 有效时间 (GTC/IOC/FOK)
     */
    private String f;
    
    /**
     * 原始数量
     */
    private String q;
    
    /**
     * 订单价格
     */
    private String p;
    
    /**
     * 订单当前状态
     * @see OrderStatus
     */
    private String X;
    
    /**
     * 事件类型 (NEW/CANCELED/TRADE...)
     * @see ExecutionType
     */
    private String x;
    
    /**
     * 累计已成交数量
     */
    private String z;
    
    /**
     * 累计已成交金额
     */
    private String Z;
    
    /**
     * 本次成交数量
     */
    private String l;
    
    /**
     * 本次成交价格
     */
    private String L;
    
    /**
     * 本次成交手续费
     */
    private String n;
    
    /**
     * 手续费资产
     */
    private String N;
    
    /**
     * 成交时间
     */
    private Long T;
    
    /**
     * 成交ID
     */
    private Long t;
    
    // ==================== 账户相关字段 (ACCOUNT_UPDATE) ====================
    
    /**
     * 最后更新时间
     */
    private Long u;
    
    /**
     * 余额列表 (JSON格式)
     */
    private String B;
    
    /**
     * 持仓列表 (JSON格式)
     */
    private String P;
    
    // ==================== 扩展字段 ====================
    
    /**
     * 错误码 (REJECTED时)
     */
    private Integer errorCode;
    
    /**
     * 错误信息 (REJECTED时)
     */
    private String errorMsg;
    
    /**
     * 业务类型 (用于追踪)
     */
    private String bizType;
    
    /**
     * 业务序列号 (幂等性)
     */
    private String bizSeq;
    
    // ==================== 枚举定义 ====================
    
    /**
     * 事件类型枚举
     */
    public static class EventType {
        /** 订单执行报告 */
        public static final String EXECUTION_REPORT = "executionReport";
        /** 账户更新 */
        public static final String ACCOUNT_UPDATE = "account";
        /** 持仓更新 */
        public static final String POSITION_UPDATE = "position";
        /** 余额更新 */
        public static final String BALANCE_UPDATE = "balance";
        /** 资金费结算 */
        public static final String FUNDING_FEE = "fundingFee";
        /** 连接确认 */
        public static final String CONNECTION_ACK = "connectionAck";
        /** 系统通知 */
        public static final String SYSTEM_NOTICE = "systemNotice";
    }
    
    /**
     * 订单状态枚举
     */
    public static class OrderStatus {
        /** 新建 */
        public static final String NEW = "NEW";
        /** 部分成交 */
        public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
        /** 完全成交 */
        public static final String FILLED = "FILLED";
        /** 已撤单 */
        public static final String CANCELED = "CANCELED";
        /** 已拒绝 */
        public static final String REJECTED = "REJECTED";
        /** 已过期 */
        public static final String EXPIRED = "EXPIRED";
    }
    
    /**
     * 执行类型枚举
     */
    public static class ExecutionType {
        /** 新订单 */
        public static final String NEW = "NEW";
        /** 撤单 */
        public static final String CANCELED = "CANCELED";
        /** 改单 */
        public static final String REPLACED = "REPLACED";
        /** 成交 */
        public static final String TRADE = "TRADE";
        /** 过期 */
        public static final String EXPIRED = "EXPIRED";
    }
    
    /**
     * 买卖方向枚举
     */
    public static class Side {
        public static final String BUY = "BUY";
        public static final String SELL = "SELL";
    }
    
    /**
     * 订单类型枚举
     */
    public static class OrderType {
        public static final String LIMIT = "LIMIT";
        public static final String MARKET = "MARKET";
        public static final String STOP = "STOP";
        public static final String STOP_MARKET = "STOP_MARKET";
        public static final String TAKE_PROFIT = "TAKE_PROFIT";
        public static final String TAKE_PROFIT_MARKET = "TAKE_PROFIT_MARKET";
        public static final String STOP_LIMIT = "STOP_LIMIT";
    }
    
    /**
     * 有效时间枚举
     */
    public static class TimeInForce {
        /** 一直有效 */
        public static final String GTC = "GTC";
        /** 立即成交或取消 */
        public static final String IOC = "IOC";
        /** 全部成交或取消 */
        public static final String FOK = "FOK";
    }
    
    // ==================== Builder 方法 ====================
    
    /**
     * 创建订单执行报告事件
     */
    public static PrivatePushEvent buildExecutionReport(
            Long userId,
            Long seq,
            String symbol,
            Long orderId,
            String clientOrderId,
            String side,
            String orderType,
            String timeInForce,
            BigDecimal quantity,
            BigDecimal price,
            String orderStatus,
            String executionType,
            BigDecimal filledQuantity,
            BigDecimal filledAmount,
            BigDecimal lastFilledQty,
            BigDecimal lastFilledPrice,
            BigDecimal fee,
            String feeAsset,
            Long tradeTime,
            Long tradeId
    ) {
        return PrivatePushEvent.builder()
                .e(EventType.EXECUTION_REPORT)
                .E(System.currentTimeMillis())
                .userId(userId)
                .seq(seq)
                .s(symbol)
                .i(orderId)
                .c(clientOrderId)
                .S(side)
                .o(orderType)
                .f(timeInForce)
                .q(quantity != null ? quantity.toPlainString() : "0")
                .p(price != null ? price.toPlainString() : "0")
                .X(orderStatus)
                .x(executionType)
                .z(filledQuantity != null ? filledQuantity.toPlainString() : "0")
                .Z(filledAmount != null ? filledAmount.toPlainString() : "0")
                .l(lastFilledQty != null ? lastFilledQty.toPlainString() : "0")
                .L(lastFilledPrice != null ? lastFilledPrice.toPlainString() : "0")
                .n(fee != null ? fee.toPlainString() : "0")
                .N(feeAsset)
                .T(tradeTime)
                .t(tradeId)
                .build();
    }
    
    /**
     * 创建账户更新事件
     */
    public static PrivatePushEvent buildAccountUpdate(
            Long userId,
            Long seq,
            String balancesJson
    ) {
        return PrivatePushEvent.builder()
                .e(EventType.ACCOUNT_UPDATE)
                .E(System.currentTimeMillis())
                .userId(userId)
                .seq(seq)
                .u(System.currentTimeMillis())
                .B(balancesJson)
                .build();
    }
    
    /**
     * 创建连接确认事件
     */
    public static PrivatePushEvent buildConnectionAck(Long userId, String sessionId) {
        return PrivatePushEvent.builder()
                .e(EventType.CONNECTION_ACK)
                .E(System.currentTimeMillis())
                .userId(userId)
                .build();
    }
}
