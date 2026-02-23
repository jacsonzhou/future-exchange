package com.exchange.oms.client;

import com.exchange.common.proto.event.OrderCommand;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 撮合引擎客户端
 */
@FeignClient(name = "match-engine-core", path = "/internal/match")
public interface MatchEngineClient {
    
    /**
     * 提交订单到撮合引擎
     */
    @PostMapping("/submit")
    void submitOrder(@RequestBody OrderCommand command);
}




