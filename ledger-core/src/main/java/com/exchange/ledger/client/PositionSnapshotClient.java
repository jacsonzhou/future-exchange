package com.exchange.ledger.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;

/**
 * 持仓快照 Feign 客户端（Ledger → Position Snapshot）
 *
 * 用途：Ledger 记账前查询用户净持仓，用于拆分平仓/开仓分录。
 * Net Mode 下反向超仓交易时，需要根据持仓量正确区分平仓和开仓的保证金流转方向。
 */
@FeignClient(name = "position-snapshot-core", path = "/internal/position")
public interface PositionSnapshotClient {

    /**
     * 查询用户净持仓（Net Mode）
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 净持仓（正数=净多头，负数=净空头，0=无持仓）
     */
    @GetMapping("/{userId}/{symbol}/net")
    BigDecimal getNetPosition(@PathVariable("userId") Long userId, @PathVariable("symbol") String symbol);
}
