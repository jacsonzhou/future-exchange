package com.exchange.funding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 资金费率结算服务启动类
 *
 * 端口: 8088
 * 职责:
 * - 资金费率计算
 * - 资金费用结算
 * - 历史费率查询
 * - 预估费率计算
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
public class FundingRateApplication {
    
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(FundingRateApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8088");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           资金费率服务启动成功                          ║");
        System.out.println("║           端口: " + port + "                                    ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
