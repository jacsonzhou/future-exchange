package com.exchange.oms.client;

import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

/**
 * 账本服务客户端（OMS -> Ledger）
 */
@FeignClient(name = "ledger-core", path = "/internal/ledger")
public interface LedgerClient {

    /**
     * 冻结保证金（下单）
     */
    @PostMapping("/freeze")
    void freezeMargin(@RequestBody FreezeRequest request);

    /**
     * 解冻保证金（撤单/成交释放）
     */
    @PostMapping("/unfreeze")
    void unfreezeMargin(@RequestBody UnfreezeRequest request);

    @Data
    class FreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
    }

    @Data
    class UnfreezeRequest {
        private Long userId;
        private String currency;
        private BigDecimal amount;
        private Long orderId;
    }
}
