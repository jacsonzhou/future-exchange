package com.exchange.snapshot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 快照服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
public class SnapshotApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(SnapshotApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8094");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           账户快照服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
