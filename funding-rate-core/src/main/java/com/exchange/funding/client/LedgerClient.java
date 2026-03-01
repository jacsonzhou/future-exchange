package com.exchange.funding.client;

import com.exchange.common.core.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 账本服务客户端
 */
@FeignClient(name = "ledger-core")
public interface LedgerClient {

    /**
     * 创建资金费用账本分录
     *
     * @param request 记账请求
     * @return 记账结果
     */
    @PostMapping("/api/v1/ledger/funding-fee")
    Result<LedgerEntryDTO> createFundingFeeLedger(@RequestBody FundingFeeLedgerRequest request);
}
