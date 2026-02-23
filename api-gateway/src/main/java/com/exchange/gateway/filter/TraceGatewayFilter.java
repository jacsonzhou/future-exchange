package com.exchange.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * 链路追踪网关过滤器（响应式）
 * 
 * 职责：
 * 1. 生成或提取 traceId
 * 2. 生成 requestId
 * 3. 记录请求开始时间和耗时
 * 4. 透传到下游服务和响应头
 * 
 * 执行顺序：Ordered.HIGHEST_PRECEDENCE（最先执行）
 */
@Slf4j
@Component
public class TraceGatewayFilter implements GlobalFilter, Ordered {
    
    private static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_TRACE_ID_LOWER = "x-trace-id";
    private static final String HEADER_REQUEST_ID_LOWER = "x-request-id";
    
    private static final String CONTEXT_KEY_TRACE_ID = "traceId";
    private static final String CONTEXT_KEY_REQUEST_ID = "requestId";
    private static final String CONTEXT_KEY_START_TIME = "startTime";
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        
        // 1. 提取或生成 traceId
        String traceId = extractTraceId(request);
        String requestId = generateRequestId();
        long startTime = System.currentTimeMillis();
        
        // 2. 设置到 MDC（用于日志）
        MDC.put(CONTEXT_KEY_TRACE_ID, traceId);
        MDC.put(CONTEXT_KEY_REQUEST_ID, requestId);
        
        // 3. 添加到响应头
        response.getHeaders().set(HEADER_TRACE_ID, traceId);
        response.getHeaders().set(HEADER_REQUEST_ID, requestId);
        
        // 4. 透传 traceId 到下游服务
        ServerHttpRequest mutatedRequest = request.mutate()
            .header(HEADER_TRACE_ID, traceId)
            .header(HEADER_REQUEST_ID, requestId)
            .build();
        
        String path = request.getURI().getPath();
        String method = request.getMethod().name();
        
        log.info("[Gateway] Request started: method={}, path={}, traceId={}, requestId={}",
            method, path, traceId, requestId);
        
        // 5. 将追踪信息放入 Reactor Context
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
            .contextWrite(Context.of(
                CONTEXT_KEY_TRACE_ID, traceId,
                CONTEXT_KEY_REQUEST_ID, requestId,
                CONTEXT_KEY_START_TIME, startTime
            ))
            .doFinally(signalType -> {
                // 6. 请求完成，记录日志
                long duration = System.currentTimeMillis() - startTime;
                int statusCode = response.getStatusCode() != null ? 
                    response.getStatusCode().value() : 0;
                
                log.info("[Gateway] Request completed: method={}, path={}, traceId={}, status={}, duration={}ms",
                    method, path, traceId, statusCode, duration);
                
                // 清理 MDC
                MDC.clear();
            });
    }
    
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 最先执行
    }
    
    /**
     * 提取 traceId（优先从请求头，不存在则生成）
     */
    private String extractTraceId(ServerHttpRequest request) {
        // 尝试从请求头获取（支持大小写）
        String traceId = request.getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null) {
            traceId = request.getHeaders().getFirst(HEADER_TRACE_ID_LOWER);
        }
        
        // 不存在则生成新的
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }
        
        return traceId;
    }
    
    /**
     * 生成 traceId
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * 生成 requestId
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
