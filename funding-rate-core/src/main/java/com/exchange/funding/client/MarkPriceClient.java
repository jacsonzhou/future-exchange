package com.exchange.funding.client;

import com.exchange.common.core.Result;
import com.exchange.funding.dto.MarkPriceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 标记价格服务客户端
 */
@FeignClient(name = "mark-price-service")
public interface MarkPriceClient {

    /**
     * 获取标记价格
     *
     * @param symbol 交易对
     * @return 标记价格
     */
    @GetMapping("/api/v1/mark-price/latest")
    Result<MarkPriceDTO> getMarkPrice(@RequestParam("symbol") String symbol);
}
