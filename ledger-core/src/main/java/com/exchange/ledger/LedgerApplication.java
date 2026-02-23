package com.exchange.ledger;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 账本服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.exchange.ledger.mapper")
public class LedgerApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(LedgerApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8084");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           账本服务启动成功                              ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
