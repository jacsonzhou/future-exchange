package com.exchange.funding.client;

/**
 * 持仓统计DTO
 */
public class PositionStatsDTO {
    private String symbol;
    private Long totalLongQty;
    private Long totalShortQty;
    private Integer longCount;
    private Integer shortCount;

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Long getTotalLongQty() {
        return totalLongQty;
    }

    public void setTotalLongQty(Long totalLongQty) {
        this.totalLongQty = totalLongQty;
    }

    public Long getTotalShortQty() {
        return totalShortQty;
    }

    public void setTotalShortQty(Long totalShortQty) {
        this.totalShortQty = totalShortQty;
    }

    public Integer getLongCount() {
        return longCount;
    }

    public void setLongCount(Integer longCount) {
        this.longCount = longCount;
    }

    public Integer getShortCount() {
        return shortCount;
    }

    public void setShortCount(Integer shortCount) {
        this.shortCount = shortCount;
    }
}
