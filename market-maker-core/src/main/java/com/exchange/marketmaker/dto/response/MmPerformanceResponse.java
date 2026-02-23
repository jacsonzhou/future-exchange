package com.exchange.marketmaker.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 做市商考核指标响应
 */
@Data
public class MmPerformanceResponse {

    private PerformanceSummary summary;
    private List<DailyPerformance> daily;

    @Data
    public static class PerformanceSummary {
        private String avgQuoteTimeRatio;
        private String avgSpread;
        private String avgDepth;
        private String makerVolume;
        private Boolean isQualified;
    }

    @Data
    public static class DailyPerformance {
        private String date;
        private String quoteTimeRatio;
        private String spread;
        private Integer score;
    }
}
