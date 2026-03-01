package com.exchange.snapshot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ADL记账请求（由 adl-core 内部调用）。
 */
@Data
public class AdlClearingRequest {

    private String bizType;
    private String bizSeq;
    private String symbol;
    private Long timestamp;
    private List<LedgerEntry> entries;
    private Map<String, Object> metadata;

    @Data
    public static class LedgerEntry {
        private Long userId;
        private String accountType;
        private String currency;
        private BigDecimal amount;
        private String direction;
        private String description;
    }
}
