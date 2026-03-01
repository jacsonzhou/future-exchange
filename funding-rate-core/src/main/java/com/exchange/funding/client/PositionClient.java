package com.exchange.funding.client;

import com.exchange.common.core.Result;
import com.exchange.funding.dto.PositionDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 持仓服务客户端
 */
@FeignClient(name = "position-snapshot-core")
public interface PositionClient {

    /**
     * 获取指定symbol的所有持仓
     *
     * @param symbol 交易对
     * @return 持仓列表
     */
    @GetMapping("/api/v1/position/all")
    Result<List<PositionDTO>> getAllPositions(@RequestParam("symbol") String symbol);

    /**
     * 批量获取持仓统计
     *
     * @param symbol 交易对
     * @return 持仓统计
     */
    @GetMapping("/api/v1/position/stats")
    Result<PositionStatsDTO> getPositionStats(@RequestParam("symbol") String symbol);
}
