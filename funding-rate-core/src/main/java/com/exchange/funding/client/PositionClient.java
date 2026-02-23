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
@FeignClient(name = "position-service", url = "${funding-rate.client.position.url:http://localhost:8084}")
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

/**
 * 持仓统计DTO
 */
public class PositionStatsDTO {
    private String symbol;
    private Long totalLongQty;   // 多头总持仓量
    private Long totalShortQty;  // 空头总持仓量
    private Integer longCount;   // 多头人数
    private Integer shortCount;  // 空头人数

    // Getters and Setters
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getTotalLongQty() { return totalLongQty; }
    public void setTotalLongQty(Long totalLongQty) { this.totalLongQty = totalLongQty; }

    public Long getTotalShortQty() { return totalShortQty; }
    public void setTotalShortQty(Long totalShortQty) { this.totalShortQty = totalShortQty; }

    public Integer getLongCount() { return longCount; }
    public void setLongCount(Integer longCount) { this.longCount = longCount; }

    public Integer getShortCount() { return shortCount; }
    public void setShortCount(Integer shortCount) { this.shortCount = shortCount; }
}
