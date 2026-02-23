package com.exchange.funding.client;

import com.exchange.common.core.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 账本服务客户端
 */
@FeignClient(name = "ledger-service", url = "${funding-rate.client.ledger.url:http://localhost:8086}")
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

/**
 * 资金费用记账请求
 */
class FundingFeeLedgerRequest {
    private Long userId;
    private String symbol;
    private Long fundingTime;
    private String side;           // LONG / SHORT
    private Long fundingFee;       // 正数=支付，负数=收取
    private String marginMode;     // ISOLATED / CROSS
    private String remark;

    // Getters and Setters
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getFundingTime() { return fundingTime; }
    public void setFundingTime(Long fundingTime) { this.fundingTime = fundingTime; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public Long getFundingFee() { return fundingFee; }
    public void setFundingFee(Long fundingFee) { this.fundingFee = fundingFee; }

    public String getMarginMode() { return marginMode; }
    public void setMarginMode(String marginMode) { this.marginMode = marginMode; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}

/**
 * 账本分录DTO
 */
class LedgerEntryDTO {
    private Long ledgerId;
    private Long userId;
    private String bizType;
    private Long amount;
    private Long createdAt;

    // Getters and Setters
    public Long getLedgerId() { return ledgerId; }
    public void setLedgerId(Long ledgerId) { this.ledgerId = ledgerId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
