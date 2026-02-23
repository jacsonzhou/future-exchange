package com.exchange.user.service.client;

import com.exchange.user.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

/**
 * Ledger服务Feign客户端
 * 
 * 用于调用ledger-core创建初始资金
 */
@FeignClient(name = "ledger-core", path = "/internal/ledger")
public interface LedgerClient {

    /**
     * 创建初始资金分录
     * 
     * @param request 初始资金请求
     * @return 创建结果
     */
    @PostMapping("/initial-funding")
    Result<InitialFundingResponse> createInitialFunding(@RequestBody InitialFundingRequest request);

    /**
     * 初始资金请求
     */
    class InitialFundingRequest {
        public Long accountId;
        public Long userId;
        public String asset;
        public BigDecimal amount;
        public String reason;
        
        public InitialFundingRequest(Long accountId, Long userId, String asset, BigDecimal amount, String reason) {
            this.accountId = accountId;
            this.userId = userId;
            this.asset = asset;
            this.amount = amount;
            this.reason = reason;
        }
    }

    /**
     * 初始资金响应
     */
    class InitialFundingResponse {
        public String entryId;
        public String status;
        public BigDecimal finalBalance;
    }
}
