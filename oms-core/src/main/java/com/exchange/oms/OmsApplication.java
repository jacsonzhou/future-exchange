package com.exchange.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * OMS核心服务启动类
 * 
 * 功能：
 * 1. 订单生命周期管理
 * 2. 订单状态机
 * 3. 订单幂等
 * 4. 订单事件编排
 * 5. 订单真相源（Source of Truth）
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.exchange.oms.client")
public class OmsApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(OmsApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8081");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           OMS 订单服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
