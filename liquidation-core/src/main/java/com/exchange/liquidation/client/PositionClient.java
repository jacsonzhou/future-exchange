package com.exchange.liquidation.client;

import com.exchange.liquidation.dto.PositionInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Position服务客户端
 */
@FeignClient(name = "position-snapshot-core", path = "/internal/position")
public interface PositionClient {
    
    @GetMapping("/get")
    PositionInfo getPosition(@RequestParam("positionId") Long positionId);
}
