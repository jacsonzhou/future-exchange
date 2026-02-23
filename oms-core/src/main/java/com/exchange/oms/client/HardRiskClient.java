package com.exchange.oms.client;

import com.exchange.common.proto.event.OrderCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 硬风控客户端
 * 
 * 🔥 核心改动：
 * 增加保证金和余额参数，用于风控检查"实际可用"余额
 */
@FeignClient(name = "hard-risk-core", path = "/internal/risk")
public interface HardRiskClient {
    
    /**
     * 风控检查（增强版）
     * 
     * @param command 订单命令
     * @param requiredMargin 所需保证金（单位：分）
     * @param totalBalance 用户总余额（单位：分）
     * @return 是否通过风控
     */
    @PostMapping("/check")
    Boolean checkRisk(
        @RequestBody OrderCommand command,
        @RequestParam("requiredMargin") Long requiredMargin,
        @RequestParam("totalBalance") Long totalBalance
    );
}




