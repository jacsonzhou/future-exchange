package com.exchange.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 重放服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ReplayApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ReplayApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8098");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           重放服务启动成功                              ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
