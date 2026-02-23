package com.exchange.marketmaker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 做市商服务启动类
 * 
 * 端口: 8092
 * 职责:
 * - 做市商管理
 * - 批量订单处理
 * - 报价质量监控
 * - 费率优惠计算
 */
@SpringBootApplication
public class MarketMakerApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MarketMakerApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8092");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           做市商服务启动成功                            ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
