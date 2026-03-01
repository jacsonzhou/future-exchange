package com.exchange.marketmaker.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * OMS服务调用客户端
 */
@Slf4j
@Component
public class OmsClient {

    @Resource
    private RestTemplate restTemplate;

    @Value("${oms.service.url:http://localhost:9091}")
    private String omsServiceUrl;

    /**
     * 提交订单
     */
    public SubmitOrderResponse submitOrder(SubmitOrderRequest request) {
        String url = omsServiceUrl + "/api/v1/oms/order/submit";

        try {
            log.info("[OmsClient] Submit order, userId={}, symbol={}, side={}, price={}, qty={}",
                    request.getUserId(), request.getSymbol(), request.getSide(),
                    request.getPrice(), request.getQuantity());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-Id", String.valueOf(request.getUserId()));
            headers.set("X-Trace-Id", request.getTraceId());
            headers.set("X-Request-Id", request.getRequestId());

            HttpEntity<SubmitOrderRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<SubmitOrderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    SubmitOrderResponse.class
            );

            SubmitOrderResponse result = response.getBody();
            log.info("[OmsClient] Submit order result, orderId={}, success={}",
                    result != null ? result.getOrderId() : null,
                    result != null ? result.getSuccess() : false);

            return result;

        } catch (Exception e) {
            log.error("[OmsClient] Submit order failed", e);
            return SubmitOrderResponse.fail("SYSTEM_ERROR", e.getMessage());
        }
    }

    /**
     * 撤单
     */
    public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
        String url = omsServiceUrl + "/api/v1/oms/order/cancel";

        try {
            log.info("[OmsClient] Cancel order, userId={}, orderId={}",
                    request.getUserId(), request.getOrderId());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-User-Id", String.valueOf(request.getUserId()));
            headers.set("X-Trace-Id", request.getTraceId());
            headers.set("X-Request-Id", request.getRequestId());

            HttpEntity<CancelOrderRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<CancelOrderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    CancelOrderResponse.class
            );

            CancelOrderResponse result = response.getBody();
            log.info("[OmsClient] Cancel order result, orderId={}, success={}",
                    result != null ? result.getOrderId() : null,
                    result != null ? result.getSuccess() : false);

            return result;

        } catch (Exception e) {
            log.error("[OmsClient] Cancel order failed", e);
            return CancelOrderResponse.fail("SYSTEM_ERROR", e.getMessage());
        }
    }

    /**
     * 查询订单
     */
    public QueryOrderResponse queryOrder(Long userId, String orderId) {
        String url = omsServiceUrl + "/api/v1/oms/order/query?orderId=" + orderId;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", String.valueOf(userId));

            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<QueryOrderResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    QueryOrderResponse.class
            );

            return response.getBody();

        } catch (Exception e) {
            log.error("[OmsClient] Query order failed, orderId={}", orderId, e);
            return null;
        }
    }

    /**
     * 批量查询订单（按条件）
     */
    public Map<String, Object> queryOrders(Long userId, String symbol, String side, String status) {
        // TODO: 如果OMS提供了批量查询接口，这里调用
        // 否则需要直接查询数据库
        return new HashMap<>();
    }

    // DTO类定义

    public static class SubmitOrderRequest {
        private String traceId;
        private String requestId;
        private Long userId;
        private String clientOrderId;
        private String symbol;
        private String side;
        private String type;
        private String price;
        private String quantity;
        private String timeInForce;

        // Getters and Setters
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public String getClientOrderId() { return clientOrderId; }
        public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }

        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }

        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity; }

        public String getTimeInForce() { return timeInForce; }
        public void setTimeInForce(String timeInForce) { this.timeInForce = timeInForce; }
    }

    public static class SubmitOrderResponse {
        private String orderId;
        private String status;
        private String clientOrderId;
        private Boolean success;
        private String errorCode;
        private String errorMessage;

        public static SubmitOrderResponse fail(String errorCode, String errorMessage) {
            SubmitOrderResponse response = new SubmitOrderResponse();
            response.setSuccess(false);
            response.setErrorCode(errorCode);
            response.setErrorMessage(errorMessage);
            return response;
        }

        // Getters and Setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getClientOrderId() { return clientOrderId; }
        public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }

        public Boolean getSuccess() { return success; }
        public void setSuccess(Boolean success) { this.success = success; }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public static class CancelOrderRequest {
        private String traceId;
        private String requestId;
        private Long userId;
        private String orderId;
        private String clientOrderId;

        // Getters and Setters
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getClientOrderId() { return clientOrderId; }
        public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    }

    public static class CancelOrderResponse {
        private String orderId;
        private String status;
        private Boolean success;
        private String errorCode;
        private String errorMessage;

        public static CancelOrderResponse fail(String errorCode, String errorMessage) {
            CancelOrderResponse response = new CancelOrderResponse();
            response.setSuccess(false);
            response.setErrorCode(errorCode);
            response.setErrorMessage(errorMessage);
            return response;
        }

        // Getters and Setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Boolean getSuccess() { return success; }
        public void setSuccess(Boolean success) { this.success = success; }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public static class QueryOrderResponse {
        private String orderId;
        private String symbol;
        private String side;
        private String status;
        private String price;
        private String quantity;
        private String filledQuantity;

        // Getters and Setters
        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }

        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }

        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity; }

        public String getFilledQuantity() { return filledQuantity; }
        public void setFilledQuantity(String filledQuantity) { this.filledQuantity = filledQuantity; }
    }
}
