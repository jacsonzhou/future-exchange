package com.exchange.snapshot.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Ledger Entry DTO（与 ledger-core 的 LedgerEntry 实体字段对齐）
 *
 * 用途：
 * 1. Feign Client 调用 ledger-core 查询接口时的反序列化对象
 * 2. snapshot-account-core 按 ref_trade_id 聚合为 TradeEntryEvent
 */
@Data
public class LedgerEntryDto {

    private Long entryId;
    private Long userId;
    private Integer accountType;
    private String currency;
    private BigDecimal debit;
    private BigDecimal credit;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String businessType;
    private String refTradeId;
    private Long refOrderId;
    private String refEventId;
    private Long pairEntryId;
    private Long bizSeq;
    private String idempotentKey;
    private Long createdAt;
}
