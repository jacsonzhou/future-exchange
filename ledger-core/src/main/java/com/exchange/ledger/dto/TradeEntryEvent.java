package com.exchange.ledger.dto;

import com.exchange.ledger.entity.LedgerEntry;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Trade Entry Event（Ledger发布的事件）
 *
 * 用途：
 * 1. Ledger-Core 写完账后发布到Kafka
 * 2. AccountSnapshotService 消费此事件
 * 3. 支持Replay重放
 *
 * Topic: trade-entry-{symbol}
 * Partition: 1（单Symbol单线程）
 * Key: symbol
 *
 * 🔥 重要修复：
 * - 添加成交原始信息（price, quantity等）
 * - 确保Position Service能正确计算持仓成本
 * - 避免从分录中错误提取价格导致的计算问题
 */
@Data
public class TradeEntryEvent {

    /**
     * 成交ID（幂等键）
     */
    private String tradeId;

    /**
     * Symbol内递增序列号
     */
    private Long sequence;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 分录列表（双录）
     */
    private List<LedgerEntry> entries;

    /**
     * 事件时间
     */
    private Long eventTime;

    /**
     * 全局业务序列号（Replay用）
     */
    private Long bizSeq;

    // ==================== 🔥 新增：成交原始信息（用于Position计算） ====================

    /**
     * 成交价格
     * 用于Position Service正确计算持仓成本
     */
    private BigDecimal price;

    /**
     * 成交数量
     * 用于Position Service正确计算持仓变动
     */
    private BigDecimal quantity;

    /**
     * Maker是否买方
     * true: Maker买入（Taker卖出）
     * false: Maker卖出（Taker买入）
     */
    private Boolean isBuyerMaker;

    /**
     * Maker用户ID
     */
    private Long makerUserId;

    /**
     * Taker用户ID
     */
    private Long takerUserId;

    /**
     * Maker订单ID
     */
    private Long makerOrderId;

    /**
     * Taker订单ID
     */
    private Long takerOrderId;

    /**
     * Maker手续费
     */
    private BigDecimal makerFee;

    /**
     * Taker手续费
     */
    private BigDecimal takerFee;

    /**
     * 执行模式：MATCH_ENGINE / CFD_DEALER
     */
    private String executionMode;

    /**
     * 流动性来源：如 BINANCE_REF
     */
    private String liquiditySource;

    /**
     * CFD 平台对手方账户ID
     */
    private Long dealerAccountId;

    /**
     * 参考行情来源 Topic
     */
    private String referenceTopic;

    /**
     * 参考行情来源 offset
     */
    private Long referenceOffset;

    /**
     * 参考行情事件时间（毫秒）
     */
    private Long referenceEventTime;
}
