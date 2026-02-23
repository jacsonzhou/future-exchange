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
 * 订单服务客户端
 *
 * 用于调用 Order Service 查询挂单信息
 */
@Slf4j
@Component
public class OrderServiceClient {

    @Value("${service.order.url:http://localhost:9091}")
    private String orderServiceUrl;

    private final RestTemplate restTemplate;

    public OrderServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 检查用户是否有挂单
     *
     * @param userId 用户ID
     * @return 是否有挂单
     */
    public boolean hasOpenOrders(Long userId) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId + "/has-open-orders";
            Boolean result = restTemplate.getForObject(url, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to check open orders, userId={}", userId, e);
            // 查询失败时保守处理，假设有挂单
            return true;
        }
    }

    /**
     * 检查用户在指定交易对是否有挂单
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 是否有挂单
     */
    public boolean hasOpenOrders(Long userId, String symbol) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId +
                    "/symbol/" + symbol + "/has-open-orders";
            Boolean result = restTemplate.getForObject(url, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to check open orders, userId={}, symbol={}",
                    userId, symbol, e);
            // 查询失败时保守处理，假设有挂单
            return true;
        }
    }

    /**
     * 检查仓位是否有关联挂单
     *
     * @param positionId 仓位ID
     * @return 是否有挂单
     */
    public boolean hasOpenOrdersForPosition(Long positionId) {
        try {
            String url = orderServiceUrl + "/internal/order/position/" + positionId +
                    "/has-open-orders";
            Boolean result = restTemplate.getForObject(url, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to check open orders for position, positionId={}",
                    positionId, e);
            // 查询失败时保守处理，假设有挂单
            return true;
        }
    }

    /**
     * 查询用户挂单列表
     *
     * @param userId 用户ID
     * @return 挂单列表
     */
    public List<OrderInfo> getOpenOrders(Long userId) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId + "/open-orders";
            return restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<OrderInfo>>() {}).getBody();
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to get open orders, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 查询用户在指定交易对的挂单列表
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 挂单列表
     */
    public List<OrderInfo> getOpenOrders(Long userId, String symbol) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId +
                    "/symbol/" + symbol + "/open-orders";
            return restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<OrderInfo>>() {}).getBody();
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to get open orders, userId={}, symbol={}",
                    userId, symbol, e);
            return null;
        }
    }

    /**
     * 统计用户挂单数量
     *
     * @param userId 用户ID
     * @return 挂单数量
     */
    public int countOpenOrders(Long userId) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId + "/open-orders-count";
            Integer result = restTemplate.getForObject(url, Integer.class);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to count open orders, userId={}", userId, e);
            return 0;
        }
    }

    /**
     * 统计用户在指定交易对的挂单数量
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 挂单数量
     */
    public int countOpenOrders(Long userId, String symbol) {
        try {
            String url = orderServiceUrl + "/internal/order/user/" + userId +
                    "/symbol/" + symbol + "/open-orders-count";
            Integer result = restTemplate.getForObject(url, Integer.class);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Failed to count open orders, userId={}, symbol={}",
                    userId, symbol, e);
            return 0;
        }
    }

    // ==================== DTO ====================

    /**
     * 订单信息DTO
     */
    @Data
    public static class OrderInfo {
        private Long orderId;             // 订单ID
        private Long userId;              // 用户ID
        private String symbol;            // 交易对
        private Integer side;             // 方向：1=买入开多/平空, 2=卖出开空/平多
        private Integer orderType;        // 订单类型：1=限价, 2=市价
        private Long price;               // 价格
        private Long quantity;            // 数量
        private Long filledQty;           // 已成交数量
        private String status;            // 状态
        private Long positionId;          // 关联仓位ID
        private Long createdAt;           // 创建时间
    }
}
