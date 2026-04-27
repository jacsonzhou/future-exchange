package com.exchange.position;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Position Snapshot Core Application
 * 
 * 持仓快照服务
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class PositionApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PositionApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8093");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           持仓快照服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}



