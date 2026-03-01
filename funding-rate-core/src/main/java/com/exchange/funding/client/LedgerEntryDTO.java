package com.exchange.funding.client;

/**
 * 账本分录DTO
 */
public class LedgerEntryDTO {
    private Long ledgerId;
    private Long userId;
    private String bizType;
    private Long amount;
    private Long createdAt;

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
