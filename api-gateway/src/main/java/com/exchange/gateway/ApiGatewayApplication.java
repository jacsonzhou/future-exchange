package com.exchange.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * API Gateway 启动类（响应式）
 * 
 * 架构升级：
 * - 从 Spring Boot Web (Servlet) 升级到 Spring Cloud Gateway (Reactive)
 * - 移除 Feign 依赖，改为纯路由模式
 * - 使用响应式编程提升并发性能
 * 
 * 职责：
 * 1. 统一入口：认证、限流、路由
 * 2. 纯网关：无业务逻辑，只负责横切面功能
 * 3. 响应式：支持 10万+ 并发连接
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties
public class ApiGatewayApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ApiGatewayApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8080");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           API 网关服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}



