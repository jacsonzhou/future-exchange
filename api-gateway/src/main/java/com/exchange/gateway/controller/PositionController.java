package com.exchange.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 持仓查询代理控制器（网关兜底）
 *
 * 说明：
 * - 当 Nacos Gateway 路由配置缺失 /api/v1/position/** 时，仍可通过该控制器访问持仓接口
 * - 下游通过服务发现调用，不硬编码地址
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/position")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class PositionController {

    private final RestTemplate loadBalancedRestTemplate;

    public PositionController(@Qualifier("loadBalancedRestTemplate") RestTemplate loadBalancedRestTemplate) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
    }

    /**
     * 查询用户持仓列表
     * GET /api/v1/position/list?userId=xxx
     */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam("userId") Long userId) {
        try {
            String url = "http://position-snapshot-core/api/v1/position/list?userId=" + userId;
            Object result = loadBalancedRestTemplate.getForObject(url, Object.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[PositionController] Query position list failed, userId={}", userId, e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", 500);
            error.put("message", "Failed to query positions: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
