package com.exchange.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * 熔断降级控制器
 * 
 * 当后端服务不可用时，返回降级响应
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {
    
    /**
     * 订单服务降级
     */
    @RequestMapping("/order")
    public Mono<ResponseEntity<Map<String, Object>>> orderFallback() {
        log.warn("[Fallback] Order service is unavailable");
        
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "success", false,
                "code", 503,
                "message", "Order service is temporarily unavailable. Please try again later.",
                "timestamp", Instant.now().toEpochMilli()
            )));
    }
    
    /**
     * 用户服务降级
     */
    @RequestMapping("/user")
    public Mono<ResponseEntity<Map<String, Object>>> userFallback() {
        log.warn("[Fallback] User service is unavailable");
        
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "success", false,
                "code", 503,
                "message", "User service is temporarily unavailable. Please try again later.",
                "timestamp", Instant.now().toEpochMilli()
            )));
    }
    
    /**
     * 行情服务降级
     */
    @RequestMapping("/market")
    public Mono<ResponseEntity<Map<String, Object>>> marketFallback() {
        log.warn("[Fallback] Market data service is unavailable");
        
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "success", false,
                "code", 503,
                "message", "Market data service is temporarily unavailable. Please try again later.",
                "timestamp", Instant.now().toEpochMilli()
            )));
    }
    
    /**
     * 通用降级
     */
    @RequestMapping("/default")
    public Mono<ResponseEntity<Map<String, Object>>> defaultFallback() {
        log.warn("[Fallback] Service is unavailable");
        
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "success", false,
                "code", 503,
                "message", "Service is temporarily unavailable. Please try again later.",
                "timestamp", Instant.now().toEpochMilli()
            )));
    }
}
