package com.exchange.funding.client;

import com.exchange.common.core.Result;
import com.exchange.funding.dto.IndexPriceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 指数价格服务客户端
 */
@FeignClient(name = "index-price-service")
public interface IndexPriceClient {

    /**
     * 获取指数价格
     *
     * @param symbol 交易对
     * @return 指数价格
     */
    @GetMapping("/api/v1/index-price/latest")
    Result<IndexPriceDTO> getIndexPrice(@RequestParam("symbol") String symbol);
}
