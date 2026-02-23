package com.exchange.adl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ADL自动减仓服务启动类
 * 
 * 端口: 8091
 * 职责:
 * - ADL触发检测
 * - ADL优先级计算
 * - ADL执行
 * - 保险基金管理
 */
@SpringBootApplication
@EnableScheduling
public class AdlApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AdlApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8091");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           ADL 自动减仓服务启动成功                      ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
