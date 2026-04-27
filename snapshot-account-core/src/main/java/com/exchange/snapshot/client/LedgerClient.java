package com.exchange.snapshot.client;

import com.exchange.snapshot.dto.LedgerEntryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Ledger Core Feign Client（灾备 Replay 专用）
 *
 * 🔥 核心职责：
 * 1. 调用 ledger-core 分页查询 ledger_entry 接口
 * 2. 用于 Kafka retention 到期后的直接 DB Replay
 *
 * 对标：Binance / OKX / Bybit 级别灾备架构
 */
@FeignClient(name = "ledger-core", path = "/internal/ledger")
public interface LedgerClient {

    /**
     * 分页查询 Ledger Entry
     *
     * @param request 查询条件（userId / startBizSeq / limit）
     * @return LedgerEntry 列表（已按 biz_seq 升序排序）
     */
    @PostMapping("/entries/query")
    List<LedgerEntryDto> queryLedgerEntries(@RequestBody QueryLedgerEntriesRequest request);

    /**
     * 查询请求 DTO
     */
    class QueryLedgerEntriesRequest {
        private Long userId;
        private Long startBizSeq;
        private Integer limit;

        public QueryLedgerEntriesRequest() {}

        public QueryLedgerEntriesRequest(Long userId, Long startBizSeq, Integer limit) {
            this.userId = userId;
            this.startBizSeq = startBizSeq;
            this.limit = limit;
        }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getStartBizSeq() { return startBizSeq; }
        public void setStartBizSeq(Long startBizSeq) { this.startBizSeq = startBizSeq; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
    }
}
