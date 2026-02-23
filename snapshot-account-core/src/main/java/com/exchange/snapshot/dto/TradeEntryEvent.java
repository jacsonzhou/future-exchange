package com.exchange.snapshot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Trade Entry Event（从Ledger接收）
 * 
 * Topic: trade-entry-{symbol}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeEntryEvent {
    
    private String tradeId;
    private Long sequence;
    private String symbol;
    private List<LedgerEntry> entries;
    private Long eventTime;
    private Long bizSeq;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LedgerEntry {
        private Long entryId;
        private Long userId;
        private Integer accountType;
        private String currency;
        private java.math.BigDecimal debit;
        private java.math.BigDecimal credit;
        private java.math.BigDecimal balanceBefore;
        private java.math.BigDecimal balanceAfter;
        private Long pairEntryId;
        private String businessType;
        private String refTradeId;
        private Long refOrderId;
        private String refEventId;
        private Long bizSeq;
        private String idempotentKey;
        private Long createdAt;
        // 添加 Jackson 忽略未知字段
    }
}

