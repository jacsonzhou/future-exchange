package com.exchange.oms.client;

import com.exchange.oms.dto.CheckOrderRiskRequest;
import com.exchange.oms.dto.CheckOrderRiskResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 硬风控客户端
 *
 * 🔥 修复记录：
 * 1. 返回类型从 Boolean 改为 CheckOrderRiskResponse，与服务端契约一致
 * 2. 请求体从 OrderCommand 改为 CheckOrderRiskRequest，解决字段绑定失败问题
 */
@FeignClient(name = "hard-risk-core", path = "/internal/risk")
public interface HardRiskClient {

    /**
     * 风控检查（增强版）
     *
     * @param request      风控检查请求（字段类型须与服务端严格对齐）
     * @param requiredMargin 所需保证金（单位：分）
     * @param totalBalance   用户总余额（单位：分）
     * @return 风控检查详细响应
     */
    @PostMapping("/check")
    CheckOrderRiskResponse checkRisk(
            @RequestBody CheckOrderRiskRequest request,
            @RequestParam("requiredMargin") Long requiredMargin,
            @RequestParam("totalBalance") Long totalBalance
    );
}
