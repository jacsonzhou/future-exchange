package com.exchange.market.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Match Engine Feign 客户端
 * 
 * 用于主动向 Match Engine 请求订单簿快照（重启恢复场景）
 */
@FeignClient(name = "match-engine-core", path = "/api/v1/match")
public interface MatchEngineClient {

    /**
     * 获取订单簿深度快照
     *
     * @param symbol 交易对
     * @param depth  深度档位数
     * @return 深度数据 {symbol, bids, asks, timestamp}
     */
    @GetMapping("/orderbook/depth/{symbol}")
    Map<String, Object> getOrderBookDepth(@PathVariable("symbol") String symbol,
                                          @RequestParam("depth") int depth);
}
