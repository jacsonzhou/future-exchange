package com.exchange.margin.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 仓位服务客户端
 *
 * 用于调用 Position Service 查询持仓信息
 */
@Slf4j
@Component
public class PositionServiceClient {

    @Value("${service.position.url:http://localhost:8084}")
    private String positionServiceUrl;

    private final RestTemplate restTemplate;

    public PositionServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询用户所有仓位
     *
     * @param userId 用户ID
     * @return 仓位列表
     */
    public List<PositionInfo> getUserPositions(Long userId) {
        try {
            String url = positionServiceUrl + "/internal/position/user/" + userId;
            return restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<PositionInfo>>() {}).getBody();
        } catch (Exception e) {
            log.error("[PositionServiceClient] Failed to get user positions, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 查询单个仓位信息
     *
     * @param positionId 仓位ID
     * @return 仓位信息
     */
    public PositionInfo getPosition(Long positionId) {
        try {
            String url = positionServiceUrl + "/internal/position/" + positionId;
            return restTemplate.getForObject(url, PositionInfo.class);
        } catch (Exception e) {
            log.error("[PositionServiceClient] Failed to get position, positionId={}", positionId, e);
            return null;
        }
    }

    /**
     * 仓位信息DTO
     */
    @Data
    public static class PositionInfo {
        private Long positionId;
        private Long userId;
        private String symbol;
        private Integer side;              // 1=多头, 2=空头
        private Long positionQty;          // 持仓数量
        private Long entryPrice;           // 开仓均价
        private Long markPrice;            // 标记价格
        private Long positionValue;        // 仓位价值
        private Long unrealizedPnl;        // 未实现盈亏
        private Long realizedPnl;          // 已实现盈亏
        private Integer leverage;          // 杠杆倍数
        private String marginMode;         // 保证金模式
    }
}
