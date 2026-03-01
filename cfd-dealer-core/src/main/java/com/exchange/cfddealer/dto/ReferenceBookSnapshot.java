package com.exchange.cfddealer.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReferenceBookSnapshot {

    private String symbol;
    private Long eventTime;
    private String topic;
    private Long offset;
    private String bestBid;
    private String bestAsk;
    private List<PriceLevel> bidsTopN = new ArrayList<>();
    private List<PriceLevel> asksTopN = new ArrayList<>();
    private String source;
    private Long stalenessMs;

    @Data
    public static class PriceLevel {
        private String price;
        private String quantity;
    }
}
