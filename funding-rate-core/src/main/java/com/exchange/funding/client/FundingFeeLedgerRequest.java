package com.exchange.funding.client;

/**
 * 资金费用记账请求
 */
public class FundingFeeLedgerRequest {
    private Long userId;
    private String symbol;
    private Long fundingTime;
    private String side;
    private Long fundingFee;
    private String marginMode;
    private String remark;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Long getFundingTime() {
        return fundingTime;
    }

    public void setFundingTime(Long fundingTime) {
        this.fundingTime = fundingTime;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public Long getFundingFee() {
        return fundingFee;
    }

    public void setFundingFee(Long fundingFee) {
        this.fundingFee = fundingFee;
    }

    public String getMarginMode() {
        return marginMode;
    }

    public void setMarginMode(String marginMode) {
        this.marginMode = marginMode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
