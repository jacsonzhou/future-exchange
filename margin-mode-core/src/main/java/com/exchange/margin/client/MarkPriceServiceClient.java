package com.exchange.margin.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 标记价格服务客户端
 *
 * 用于调用 MarkPrice Service 获取标记价格
 */
@Slf4j
@Component
public class MarkPriceServiceClient {

    @Value("${service.markprice.url:http://localhost:8089}")
    private String markPriceServiceUrl;

    private final RestTemplate restTemplate;

    public MarkPriceServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取标记价格
     *
     * @param symbol 交易对
     * @return 标记价格信息
     */
    public MarkPriceInfo getMarkPrice(String symbol) {
        try {
            String url = markPriceServiceUrl + "/internal/markprice/" + symbol;
            return restTemplate.getForObject(url, MarkPriceInfo.class);
        } catch (Exception e) {
            log.error("[MarkPriceServiceClient] Failed to get mark price, symbol={}", symbol, e);
            return null;
        }
    }

    /**
     * 标记价格信息DTO
     */
    @Data
    public static class MarkPriceInfo {
        private String symbol;
        private Long markPrice;            // 标记价格
        private Long indexPrice;           // 指数价格
        private Long lastPrice;            // 最新成交价
        private Long fundingRate;          // 资金费率
        private Long nextFundingTime;      // 下次资金费率结算时间
    }
}
