package com.exchange.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 熔断降级控制器
 * 
 * 当后端服务不可用时，返回降级响应
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";
    private static final String HEADER_CLIENT_ORDER_ID = "X-Client-Order-Id";
    private static final int CONFIRM_MAX_ROUNDS = 40;
    private static final long CONFIRM_INTERVAL_MILLIS = 150L;

    private final RestTemplate loadBalancedRestTemplate;

    public FallbackController(@Qualifier("loadBalancedRestTemplate") RestTemplate loadBalancedRestTemplate) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
    }
    
    /**
     * 订单服务降级
     */
    @RequestMapping("/order")
    public Mono<ResponseEntity<Map<String, Object>>> orderFallback(ServerWebExchange exchange) {
        log.warn("[Fallback] Order service fallback triggered, path={}", exchange.getRequest().getPath());

        return Mono.fromCallable(() -> tryConfirmOrderSubmit(exchange))
            .subscribeOn(Schedulers.boundedElastic())
            .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
            .switchIfEmpty(Mono.fromSupplier(() -> serviceUnavailable(
                "Order service is temporarily unavailable. Please try again later."
            )))
            .onErrorResume(e -> {
                log.warn("[Fallback] Order fallback confirm failed, fallback to 503: {}", e.getMessage());
                return Mono.just(serviceUnavailable(
                    "Order service is temporarily unavailable. Please try again later."
                ));
            });
    }
    
    /**
     * 用户服务降级
     */
    @RequestMapping("/user")
    public Mono<ResponseEntity<Map<String, Object>>> userFallback() {
        log.warn("[Fallback] User service is unavailable");
        
        return Mono.just(serviceUnavailable(
            "User service is temporarily unavailable. Please try again later."
        ));
    }
    
    /**
     * 行情服务降级
     */
    @RequestMapping("/market")
    public Mono<ResponseEntity<Map<String, Object>>> marketFallback() {
        log.warn("[Fallback] Market data service is unavailable");
        
        return Mono.just(serviceUnavailable(
            "Market data service is temporarily unavailable. Please try again later."
        ));
    }
    
    /**
     * 通用降级
     */
    @RequestMapping("/default")
    public Mono<ResponseEntity<Map<String, Object>>> defaultFallback() {
        log.warn("[Fallback] Service is unavailable");
        
        return Mono.just(serviceUnavailable(
            "Service is temporarily unavailable. Please try again later."
        ));
    }

    private Map<String, Object> tryConfirmOrderSubmit(ServerWebExchange exchange) {
        String userIdHeader = exchange.getRequest().getHeaders().getFirst(HEADER_USER_ID);
        List<String> clientOrderIds = resolveClientOrderIdCandidates(exchange);

        if (userIdHeader == null || userIdHeader.isBlank() || clientOrderIds.isEmpty()) {
            log.warn("[Fallback] Skip confirm due to missing userId/clientOrderId, userId={}, candidates={}",
                userIdHeader, clientOrderIds);
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_USER_ID, userIdHeader.trim());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (int round = 0; round < CONFIRM_MAX_ROUNDS; round++) {
            for (String clientOrderId : clientOrderIds) {
                String url = UriComponentsBuilder
                    .fromHttpUrl("http://oms-core/api/v1/oms/order/confirm-submit")
                    .queryParam("clientOrderId", clientOrderId)
                    .toUriString();
                try {
                    ResponseEntity<Map> response = loadBalancedRestTemplate.exchange(
                        url,
                        org.springframework.http.HttpMethod.GET,
                        entity,
                        Map.class
                    );
                    Map<String, Object> body = response.getBody();
                    if (body == null) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(body.get("success")) && body.get("orderId") != null) {
                        Map<String, Object> confirmed = new LinkedHashMap<>(body);
                        confirmed.put("confirmedByFallback", true);
                        confirmed.put("confirmedClientOrderId", clientOrderId);
                        confirmed.put("timestamp", Instant.now().toEpochMilli());
                        log.warn("[Fallback] Confirmed order submit by clientOrderId, userId={}, clientOrderId={}, orderId={}",
                            userIdHeader, clientOrderId, body.get("orderId"));
                        return confirmed;
                    }
                } catch (Exception e) {
                    log.debug("[Fallback] Confirm submit retry failed, userId={}, clientOrderId={}, round={}, error={}",
                        userIdHeader, clientOrderId, round + 1, e.getMessage());
                }
            }
            if (round < CONFIRM_MAX_ROUNDS - 1) {
                try {
                    Thread.sleep(CONFIRM_INTERVAL_MILLIS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private List<String> resolveClientOrderIdCandidates(ServerWebExchange exchange) {
        Set<String> dedupe = new HashSet<>();
        List<String> candidates = new ArrayList<>();

        String directClientOrderId = trimToNull(exchange.getRequest().getHeaders().getFirst(HEADER_CLIENT_ORDER_ID));
        if (directClientOrderId != null && dedupe.add(directClientOrderId)) {
            candidates.add(directClientOrderId);
        }

        String queryClientOrderId = trimToNull(exchange.getRequest().getQueryParams().getFirst("clientOrderId"));
        if (queryClientOrderId != null && dedupe.add(queryClientOrderId)) {
            candidates.add(queryClientOrderId);
        }

        String idempotencyKey = trimToNull(exchange.getRequest().getHeaders().getFirst(HEADER_IDEMPOTENCY_KEY));
        if (idempotencyKey != null && idempotencyKey.regionMatches(true, 0, "idem-", 0, 5)) {
            String normalized = trimToNull(idempotencyKey.substring(5));
            if (normalized != null && dedupe.add(normalized)) {
                candidates.add(normalized);
            }
        }
        if (idempotencyKey != null && dedupe.add(idempotencyKey)) {
            candidates.add(idempotencyKey);
        }

        return candidates;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ResponseEntity<Map<String, Object>> serviceUnavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "success", false,
                "code", 503,
                "message", message,
                "timestamp", Instant.now().toEpochMilli()
            ));
    }
}
