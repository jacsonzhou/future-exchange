package com.exchange.oms.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 快照服务客户端
 * 
 * 🔥 核心职责：
 * 获取用户账户快照信息（余额、持仓等）
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@FeignClient(name = "snapshot-account-core", path = "/internal/snapshot")
public interface SnapshotClient {
    
    /**
     * 获取用户可用余额
     * 
     * @param userId 用户ID
     * @return 可用余额（单位：分）
     */
    @GetMapping("/balance/available")
    Long getUserAvailableBalance(@RequestParam("userId") Long userId);
    
    /**
     * 获取用户总余额
     * 
     * @param userId 用户ID
     * @return 总余额（单位：分）
     */
    @GetMapping("/balance/total")
    Long getUserTotalBalance(@RequestParam("userId") Long userId);
}
