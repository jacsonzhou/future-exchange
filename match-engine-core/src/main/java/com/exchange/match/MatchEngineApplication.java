package com.exchange.match;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Match Engine Core 启动类
 * 
 * 功能：
 * 1. 单Symbol单撮合实例
 * 2. Disruptor + 单线程撮合
 * 3. Price-Time Priority
 * 4. 内存订单簿
 * 5. 确定性可重放
 * 6. 极致性能（QPS 100k+）
 */
@SpringBootApplication
@EnableDiscoveryClient
public class MatchEngineApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MatchEngineApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8083");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           撮合引擎服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("║           Disruptor: 单线程撮合                          ║");
        System.out.println("║           OrderBook: 内存订单簿                          ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
