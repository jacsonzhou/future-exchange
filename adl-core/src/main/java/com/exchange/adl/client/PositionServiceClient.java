package com.exchange.adl.client;

import com.exchange.adl.client.dto.PositionDTO;
import com.exchange.adl.client.dto.PositionQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Position Service 客户端
 *
 * 职责：
 * 1. 获取用户持仓信息
 * 2. 查询盈利持仓列表
 * 3. 更新持仓（ADL减仓）
 */
@Slf4j
@Component
public class PositionServiceClient {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${service.position.url:http://localhost:8084}")
    private String positionServiceUrl;

    /**
     * 获取用户持仓
     */
    public PositionDTO getUserPosition(Long userId, String symbol) {
        try {
            String url = positionServiceUrl + "/api/v1/position/get?userId={userId}&symbol={symbol}";

            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("symbol", symbol);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class, params);

            if (response != null && (Integer) response.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                return convertToPositionDTO(data);
            }

            log.warn("Failed to get position: userId={}, symbol={}", userId, symbol);
            return null;

        } catch (Exception e) {
            log.error("Error calling position service", e);
            return null;
        }
    }

    /**
     * 查询盈利持仓列表
     */
    public List<PositionDTO> queryProfitablePositions(String symbol, String side, int limit) {
        try {
            String url = positionServiceUrl + "/api/v1/position/query-profitable";

            PositionQueryRequest request = new PositionQueryRequest();
            request.setSymbol(symbol);
            request.setSide(side);
            request.setLimit(limit);
            request.setOnlyProfitable(true);

            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response != null && (Integer) response.get("code") == 0) {
                List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
                return dataList.stream()
                        .map(this::convertToPositionDTO)
                        .collect(java.util.stream.Collectors.toList());
            }

            log.warn("Failed to query profitable positions: symbol={}, side={}", symbol, side);
            return List.of();

        } catch (Exception e) {
            log.error("Error calling position service", e);
            return List.of();
        }
    }

    /**
     * 校验持仓是否有效
     */
    public boolean validatePosition(Long userId, Long positionId, String expectedSide) {
        try {
            PositionDTO position = getPositionById(positionId);

            if (position == null) {
                log.warn("Position not found: positionId={}", positionId);
                return false;
            }

            // 校验用户ID
            if (!position.getUserId().equals(userId)) {
                log.warn("Position user mismatch: positionId={}, expected={}, actual={}",
                        positionId, userId, position.getUserId());
                return false;
            }

            // 校验方向
            if (!position.getSide().equalsIgnoreCase(expectedSide)) {
                log.warn("Position side mismatch: positionId={}, expected={}, actual={}",
                        positionId, expectedSide, position.getSide());
                return false;
            }

            // 校验持仓数量
            if (position.getPositionSize() == null || position.getPositionSize().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                log.warn("Position size invalid: positionId={}, size={}",
                        positionId, position.getPositionSize());
                return false;
            }

            // 校验是否盈利
            if (position.getUnrealizedPnl() == null || position.getUnrealizedPnl().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                log.warn("Position not profitable: positionId={}, pnl={}",
                        positionId, position.getUnrealizedPnl());
                return false;
            }

            // 校验账户状态
            if ("FROZEN".equalsIgnoreCase(position.getStatus())) {
                log.warn("Position frozen: positionId={}", positionId);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("Error validating position", e);
            return false;
        }
    }

    /**
     * 根据持仓ID获取持仓
     */
    public PositionDTO getPositionById(Long positionId) {
        try {
            String url = positionServiceUrl + "/api/v1/position/get-by-id?positionId={positionId}";

            Map<String, Object> params = new HashMap<>();
            params.put("positionId", positionId);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class, params);

            if (response != null && (Integer) response.get("code") == 0) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                return convertToPositionDTO(data);
            }

            return null;

        } catch (Exception e) {
            log.error("Error getting position by id", e);
            return null;
        }
    }

    /**
     * 通知Position Service执行ADL减仓
     * 注意：实际的持仓更新由Clearing Service通过事件驱动完成
     * 这里只是通知Position Service准备处理ADL
     */
    public boolean notifyAdlExecution(Long userId, Long positionId, String adlExecutionId) {
        try {
            String url = positionServiceUrl + "/internal/position/notify-adl";

            Map<String, Object> request = new HashMap<>();
            request.put("userId", userId);
            request.put("positionId", positionId);
            request.put("adlExecutionId", adlExecutionId);

            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response != null && (Integer) response.get("code") == 0) {
                log.info("ADL notification sent: positionId={}, adlExecutionId={}", positionId, adlExecutionId);
                return true;
            }

            log.warn("Failed to notify ADL: positionId={}", positionId);
            return false;

        } catch (Exception e) {
            log.error("Error notifying ADL execution", e);
            return false;
        }
    }

    /**
     * 转换为PositionDTO
     */
    private PositionDTO convertToPositionDTO(Map<String, Object> data) {
        if (data == null) {
            return null;
        }

        PositionDTO dto = new PositionDTO();
        dto.setPositionId(getLong(data, "positionId"));
        dto.setUserId(getLong(data, "userId"));
        dto.setSymbol(getString(data, "symbol"));
        dto.setSide(getString(data, "side"));
        dto.setPositionSize(getBigDecimal(data, "positionSize"));
        dto.setEntryPrice(getBigDecimal(data, "entryPrice"));
        dto.setMarkPrice(getBigDecimal(data, "markPrice"));
        dto.setLiquidationPrice(getBigDecimal(data, "liquidationPrice"));
        dto.setMarginBalance(getBigDecimal(data, "marginBalance"));
        dto.setMaintenanceMargin(getBigDecimal(data, "maintenanceMargin"));
        dto.setUnrealizedPnl(getBigDecimal(data, "unrealizedPnl"));
        dto.setRealizedPnl(getBigDecimal(data, "realizedPnl"));
        dto.setLeverage(getInteger(data, "leverage"));
        dto.setStatus(getString(data, "status"));
        dto.setCreatedAt(getLong(data, "createdAt"));
        dto.setUpdatedAt(getLong(data, "updatedAt"));

        return dto;
    }

    // 辅助方法
    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private java.math.BigDecimal getBigDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return new java.math.BigDecimal(value.toString());
        }
        return null;
    }
}
