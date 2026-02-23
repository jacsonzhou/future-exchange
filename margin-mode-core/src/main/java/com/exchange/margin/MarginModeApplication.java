package com.exchange.margin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 保证金模式服务启动类
 * 
 * 端口: 8090
 * 职责:
 * - 全仓/逐仓模式管理
 * - 保证金计算
 * - 杠杆管理
 * - 强平价计算
 */
@SpringBootApplication
public class MarginModeApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MarginModeApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8090");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           保证金模式服务启动成功                        ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
