package com.exchange.tpsl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * OMS服务客户端
 *
 * 调用订单管理服务创建平仓订单
 */
@Slf4j
@Component
public class OmsClient {

    @Autowired
    private RestTemplate restTemplate;

    private static final String OMS_BASE_URL = "http://localhost:9091";

    /**
     * 创建平仓订单
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @param side 方向 (BUY平空仓, SELL平多仓)
     * @param orderType 订单类型 (MARKET/LIMIT)
     * @param quantity 数量
     * @param price 价格 (LIMIT订单需要)
     * @param positionId 持仓ID
     * @return 订单ID
     */
    public Long createCloseOrder(Long userId, String symbol, String side, String orderType,
                                 Long quantity, Long price, Long positionId) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("userId", userId);
            request.put("symbol", symbol);
            request.put("side", side);
            request.put("orderType", orderType);
            request.put("quantity", quantity);
            request.put("positionId", positionId);
            request.put("reduceOnly", true); // 只减仓

            if ("LIMIT".equals(orderType) && price != null) {
                request.put("price", price);
            }

            // 调用OMS创建订单接口
            String url = OMS_BASE_URL + "/api/v1/order/create";
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response != null && "0".equals(String.valueOf(response.get("code")))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                Long orderId = ((Number) data.get("orderId")).longValue();
                log.info("Created close order successfully: orderId={}, userId={}, symbol={}",
                    orderId, userId, symbol);
                return orderId;
            } else {
                String msg = response != null ? String.valueOf(response.get("msg")) : "Unknown error";
                throw new RuntimeException("Failed to create close order: " + msg);
            }

        } catch (Exception e) {
            log.error("Failed to create close order via OMS: userId={}, symbol={}", userId, symbol, e);
            throw new RuntimeException("Failed to create close order", e);
        }
    }
}
